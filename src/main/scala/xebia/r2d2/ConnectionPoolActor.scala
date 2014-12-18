package xebia.r2d2

import java.sql.{ Connection, DriverManager }
import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.actor._
import org.joda.time.DateTime

import scala.collection.mutable.{ HashMap ⇒ MutableHashMap, Queue ⇒ MutableQueue }
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{ Failure, Success }

object ConnectionPoolActor {

  implicit class JodaWithDuration(first: DateTime) {
    def until(second: DateTime): Duration = {
      Duration(Math.abs(first.getMillis - second.getMillis), TimeUnit.MILLISECONDS)
    }
  }

  def props = Props[ConnectionPoolActor]

  private[r2d2] case class ConnectionMetadata(connection: Connection, created: DateTime, lastCheckout: DateTime)
  private[r2d2] case class ConnectionRequest(ref: ActorRef, created: DateTime)

  private case object RequestConnection
  private case class ReturnConnection(connection: Connection)
  private case class CreatedConnection(metadata: ConnectionMetadata)
  private case class FailedCreatingConnection(t: Throwable)
  private case object TestConnections

  class ConnectionRequestException(message: String, cause: Throwable) extends Exception(message, cause) {
    def this(message: String) = {
      this(message, null)
    }
  }

  def withTime[T](time: DateTime)(block: DateTime ⇒ T): T = block(time)

  class ConnectionRequestTimeoutException(duration: Duration)
    extends ConnectionRequestException(s"Connection request timed out, took longer than ${duration.toMillis}ms.")
  class MaxConnectionRequestsExceededException(max: Int)
    extends ConnectionRequestException(s"Max number outstanding requests has been exceeded. Max is $max.")
}

trait ConnectionPoolActor extends ConnectionPool with Actor with ActorSystemContextSupport with ActorAskSupport
    with CurrentTime with BlockingExecutionContext with DatabaseConfig with ActorLogging {

  import akka.pattern.ask
  import xebia.r2d2.ConnectionPoolActor._

  val availableConnections = MutableQueue[ConnectionMetadata]()
  val usedConnections = MutableHashMap[Connection, ConnectionMetadata]()
  val connectionRequests = MutableQueue[ConnectionRequest]()

  def totalConnections = availableConnections.size + usedConnections.size

  private[this] def getConnection: Future[Connection] = self.ask(RequestConnection).mapTo[Connection]
  private[this] def returnConnection(connection: Connection): Unit = self ! ReturnConnection(connection)

  // Interface
  override def withConnection[T](block: (Connection) ⇒ Future[T]): Future[T] = {
    getConnection.flatMap { connection: Connection ⇒
      block(connection).andThen { case _ ⇒ returnConnection(connection) }
    }
  }

  override def receive: Receive = {
    case RequestConnection    ⇒ requestConnection(sender())
    case ReturnConnection(db) ⇒ returnConnection(db)
    case CreatedConnection(metadata) ⇒
      availableConnections.enqueue(metadata)
      handOutConnection()
    case FailedCreatingConnection(t) ⇒ checkoutFailure(t)
    case TestConnections             ⇒ testConnections()
  }

  {
    context.system.scheduler.schedule(initialDelay = 5 seconds, interval = 5 seconds, self, TestConnections)
  }

  private[this] def requestConnection(sender: ActorRef): Unit = {
    if (connectionRequests.size >= maxPendingConnections) {
      sender ! Status.Failure(new MaxConnectionRequestsExceededException(maxPendingConnections))
    } else {
      connectionRequests.enqueue(ConnectionRequest(sender, now))
      if (availableConnections.isEmpty) {
        if (totalConnections < poolSize) {
          createNewConnection()
        }
      } else {
        handOutConnection()
      }
    }
  }

  private[this] def createNewConnection(): Unit = {
    Future({
      val props = new Properties()
      props.put("user", username)
      props.put("password", password)

      DriverManager.getConnection(connectionString, props)
      // TODO: Allow for post configuration like addDataType

    })(blockingExecutionContext).onComplete {
      case Success(db) ⇒ self ! CreatedConnection(ConnectionMetadata(db, now, now))
      case Failure(t)  ⇒ self ! FailedCreatingConnection(t)
    }
  }

  private[this] def handOutConnection(): Unit = {
    if (connectionRequests.nonEmpty && availableConnections.nonEmpty) {

      val metadata = availableConnections.dequeue()
      val connectionRequest = connectionRequests.dequeue()

      if (metadata.connection.isClosed) {
        closeConnection(metadata.connection)
        handOutConnection()
      } else {
        usedConnections += ((metadata.connection, metadata))
        connectionRequest.ref ! metadata.connection
      }
    }
  }

  private[this] def closeConnection(connection: Connection): Unit = {
    Future({
      connection.close()
    })(blockingExecutionContext)
  }

  private[this] def checkoutFailure(t: Throwable) = {
    val failure = new ConnectionRequestException("Failed to acquire connection", t)
    if (connectionRequests.nonEmpty) {
      connectionRequests.dequeue().ref ! Status.Failure(failure)
    } else {
      //only log it when not sent back to a user to prevent double logging
      log.error(failure, "Error during creation of connection")
    }
  }

  private[this] def testConnections(): Unit = {
    withTime(now) { now ⇒
      val expiredConnections = availableConnections.dequeueAll(metadata ⇒ isConnectionExpired(metadata, now))
      expiredConnections.foreach(metadata ⇒ closeConnection(metadata.connection))
      val timedOutConnectionRequests = connectionRequests.dequeueAll { r ⇒
        (r.created until now) > connectionRequestTimeout
      }
      if (timedOutConnectionRequests.nonEmpty) {
        timedOutConnectionRequests.foreach(_.ref ! Status.Failure(
          new ConnectionRequestTimeoutException(connectionRequestTimeout)))
      }
    }
  }

  private[this] def isConnectionExpired(metadata: ConnectionMetadata, now: DateTime = now): Boolean = {
    withTime(now) { now ⇒
      ((metadata.created until now) > maxConnectionAge) ||
        ((metadata.lastCheckout until now) > maxConnectionIdleTime)
    }
  }
}
