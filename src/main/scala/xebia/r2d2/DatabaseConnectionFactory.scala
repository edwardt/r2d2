/*
 * Copyright (c) 2013 by Floating Market BV. Alle rechten voorbehouden.
 */

package com.plotprojects.dao.postgresql

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.plotprojects.dao.postgresql.DatabaseConnectionFactoryActor._
import com.plotprojects.util.ClockDao
import java.sql.{ DriverManager, Connection }
import java.util.concurrent.TimeUnit
import java.util.Properties
import org.joda.time.DateTime
import org.postgis.PGgeometry
import org.postgresql.PGConnection
import scala.concurrent.{ ExecutionContext, Future }
import scala.slick.session.{ Session, Database }
import scala.util.{ Failure, Success }
import scala.concurrent.duration._

class DatabaseConnectionFactory(config: DatabaseConfig, blockingEc: ExecutionContext, clockDao: ClockDao, actorName: String = "DatabaseFactory")(implicit val ac: ActorSystem, ec: ExecutionContext) {
  private[this] val actor = ac.actorOf(Props(new DatabaseConnectionFactoryActor(config, blockingEc, clockDao)), actorName)

  private[this] implicit val timeout = Timeout.durationToTimeout(Duration(10, TimeUnit.SECONDS))

  def getSession: Future[Session] = {
    (actor ? GetConnection).mapTo[Session]
  }

  def returnSession(session: Session): Unit = {
    actor ! ReturnConnection(session)
  }

  def close(): Unit = {
    actor ! PoisonPill
  }
}

class DatabaseConnectionFactoryActor(config: DatabaseConfig, blockEc: ExecutionContext, clockDao: ClockDao) extends Actor with ActorLogging {
  private[this] implicit def ec = context.system.dispatcher

  {
    val delay = 5.seconds
    context.system.scheduler.schedule(delay, delay, self, TestConnections)
  }

  val pool = scala.collection.mutable.Queue[SessionMetadata]()
  val checkedOutConnections = scala.collection.mutable.HashMap[Session, SessionMetadata]()

  val pendingConnectionRequests = scala.collection.mutable.Queue[PendingConnectionRequest]()

  var totalConnections = 0

  def receive = {
    case GetConnection        ⇒ getConnection(sender)
    case ReturnConnection(db) ⇒ returnConnection(db)
    case CreatedConnection(metadata) ⇒
      pool.enqueue(metadata)
      giveOutConnection()
    case FailedCreatingConnection(t) ⇒ checkoutFailure(t)
    case TestConnections             ⇒ testConnections()
  }

  private[this] def getConnection(sender: ActorRef): Unit = {
    if (pendingConnectionRequests.size >= config.maxPendingConnections) {
      val e = new MaxOutstandingRequestsExceededException(config.maxPendingConnections)
      sender ! Status.Failure(e)
    } else {
      pendingConnectionRequests.enqueue(PendingConnectionRequest(sender, clockDao.now))
      if (pool.isEmpty) {
        if (totalConnections < config.poolSize) {
          createNewConnection()
        }
      } else {
        giveOutConnection()
      }
    }
  }

  private[this] def returnConnection(session: Session): Unit = {
    val metadataOption = checkedOutConnections.remove(session)
    if (metadataOption.isDefined) {
      val metadata = metadataOption.get
      metadata.lastCheckout = clockDao.now
      if (isConnectionExpired(metadata)) {
        closeConnection(session)
      } else {
        pool.enqueue(metadata)
        giveOutConnection()
      }
    }
  }

  private[this] def isConnectionExpired(metadata: SessionMetadata, now: DateTime = clockDao.now): Boolean = {
    val createdInterval = new org.joda.time.Duration(metadata.created, now)
    val idleInterval = new org.joda.time.Duration(metadata.lastCheckout, now)
    (createdInterval.getMillis > config.maxConnectionAge) || (idleInterval.getMillis > config.maxConnectionIdleTime)
  }

  private[this] def testConnections(): Unit = {
    val now = clockDao.now
    val expiredConnections = pool.dequeueAll(metadata ⇒ isConnectionExpired(metadata, now))
    expiredConnections.foreach(metadata ⇒ closeConnection(metadata.session))

    val expiredPendingConnectionRequests = pendingConnectionRequests.dequeueAll { r ⇒
      val d = new org.joda.time.Duration(r.created, now)
      d.getMillis > config.maxConnectionCheckoutTime
    }
    if (!expiredPendingConnectionRequests.isEmpty) {
      val e = new CheckoutTimeoutException(config.maxConnectionCheckoutTime)
      expiredPendingConnectionRequests.foreach(_.ref ! Status.Failure(e))
    }
  }

  private[this] def giveOutConnection(): Unit = {
    if (!pendingConnectionRequests.isEmpty && !pool.isEmpty) {

      val metadata = pool.dequeue()
      val connectionRequest = pendingConnectionRequests.dequeue()

      if (metadata.session.conn.isClosed) {
        closeConnection(metadata.session)
        giveOutConnection()
      } else {
        checkedOutConnections += ((metadata.session, metadata))
        connectionRequest.ref ! metadata.session
      }
    }
  }

  private[this] def createNewConnection(): Unit = {
    totalConnections += 1

    val createFuture = Future({
      val jdbcUrl = s"jdbc:postgresql://${config.host}/${config.databaseName}"

      val props = new Properties()
      props.put("user", config.username)
      props.put("password", config.password)

      val connection = DriverManager.getConnection(jdbcUrl, props)

      val pgConnection = connection.asInstanceOf[PGConnection]
      pgConnection.addDataType("geography", classOf[PGgeometry])
      pgConnection.addDataType("geometry", classOf[PGgeometry])

      val db = new Database {
        def createConnection(): Connection = connection
      }
      db.createSession()
    })(blockEc)

    createFuture.onComplete {
      case Success(db) ⇒ self ! CreatedConnection(SessionMetadata(db, clockDao.now, clockDao.now))
      case Failure(t)  ⇒ self ! FailedCreatingConnection(t)
    }
  }

  private[this] def closeConnection(session: Session): Unit = {
    totalConnections -= 1
    if (totalConnections < 0)
      log.error("Number of connections went below 0")
    Future({
      session.close()
    })(blockEc)
  }

  private[this] def checkoutFailure(t: Throwable) = {
    totalConnections -= 1
    val failure = new ConnectionCheckoutException("Failed to checkout connection", t)

    if (!pendingConnectionRequests.isEmpty) {
      val request = pendingConnectionRequests.dequeue()

      request.ref ! Status.Failure(failure)
    } else {
      //only log it when not sent back to a user to prevent double logging
      log.error(failure, "Error during creation of connection")
    }
  }
}

object DatabaseConnectionFactoryActor {
  class ConnectionCheckoutException(message: String, cause: Throwable) extends Exception(message, cause) {
    def this(message: String) = {
      this(message, null)
    }
  }
  class CheckoutTimeoutException(period: Long)
    extends ConnectionCheckoutException(s"Failed to checkout exception within ${period}ms.")
  class MaxOutstandingRequestsExceededException(max: Int)
    extends ConnectionCheckoutException(s"Max number outstanding requests has been exceeded. Max is ${max}.")

  case object GetConnection
  case class ReturnConnection(session: Session)

  private[DatabaseConnectionFactoryActor] case class CreatedConnection(metadata: SessionMetadata)
  private[DatabaseConnectionFactoryActor] case class FailedCreatingConnection(t: Throwable)
  private[DatabaseConnectionFactoryActor] case class PendingConnectionRequest(ref: ActorRef, created: DateTime)

  private[DatabaseConnectionFactoryActor] case class SessionMetadata(session: Session, created: DateTime, var lastCheckout: DateTime)

  private[DatabaseConnectionFactoryActor] case object TestConnections
}

