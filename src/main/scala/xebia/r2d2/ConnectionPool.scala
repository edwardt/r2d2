package xebia.r2d2

import java.sql.{ DriverManager, Connection }
import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.util.Timeout
import org.joda.time.{ DateTime, Duration ⇒ JodaTimeDuration }

import scala.collection.mutable.{ Queue ⇒ MutableQueue, HashMap ⇒ MutableHashMap }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.concurrent.duration._

object ConnectionPoolActor {

  def props = Props[ConnectionPoolActor]

  private[r2d2] case class ConnectionMetadata(connection: Connection, created: DateTime, lastCheckout: DateTime)
  private[r2d2] case class ConnectionRequest(ref: ActorRef, created: DateTime)

  private case object RequestConnection
  private case class ReturnConnection(connection: Connection)
  private case class CreatedConnection(metadata: ConnectionMetadata)
  private case class FailedCreatingConnection(t: Throwable)
  private case object TestConnections
}

trait ConnectionPoolActor extends Actor with ActorSystemContextSupport with ActorAskSupport with ActorLogging {

  import ConnectionPoolActor._

  // TODO: Move out!
  def config: DatabaseConfig
  def now: DateTime
  def blockingExecutionContext: ExecutionContext
  def actorSystem: ActorSystem
  private[this] implicit val timeout = Timeout.durationToTimeout(Duration(10, TimeUnit.SECONDS))

  val availableConnections = MutableQueue[ConnectionMetadata]()
  val usedConnections = MutableHashMap[Connection, ConnectionMetadata]()
  val connectionRequests = MutableQueue[ConnectionRequest]()

  def totalConnections = availableConnections.size + usedConnections.size

  import akka.pattern.ask
  private[this] def getConnection: Future[Connection] = self.ask(RequestConnection).mapTo[Connection]
  private[this] def returnConnection(connection: Connection): Unit = self ! ReturnConnection(connection)

  // Interface
  def withConnection[T](block: (Connection) ⇒ Future[T]): Future[T] = {
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
    if (connectionRequests.size >= config.maxPendingConnections) {
      sender ! Status.Failure(new MaxConnectionRequestsExceededException(config.maxPendingConnections))
    } else {
      connectionRequests.enqueue(ConnectionRequest(sender, now))
      if (availableConnections.isEmpty) {
        if (totalConnections < config.poolSize) {
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
      props.put("user", config.username)
      props.put("password", config.password)

      DriverManager.getConnection(config.connectionString, props)
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
    val failure = new ConnectionRequestException("Failed to checkout connection", t)
    if (connectionRequests.nonEmpty) {
      connectionRequests.dequeue().ref ! Status.Failure(failure)
    } else {
      //only log it when not sent back to a user to prevent double logging
      log.error(failure, "Error during creation of connection")
    }
  }

  private[this] def testConnections(): Unit = {
    val snow = now
    val expiredConnections = availableConnections.dequeueAll(metadata ⇒ isConnectionExpired(metadata, snow))
    expiredConnections.foreach(metadata ⇒ closeConnection(metadata.connection))

    val timedOutConnectionRequests = connectionRequests.dequeueAll { r ⇒
      new JodaTimeDuration(r.created, snow).getMillis > config.connectionRequestTimeout
    }

    if (timedOutConnectionRequests.nonEmpty) {
      timedOutConnectionRequests.foreach(_.ref ! Status.Failure(
        new ConnectionRequestTimeoutException(config.connectionRequestTimeout)))
    }
  }

  private[this] def isConnectionExpired(metadata: ConnectionMetadata, now: DateTime = now): Boolean = {
    val createdInterval = new JodaTimeDuration(metadata.created, now)
    val idleInterval = new JodaTimeDuration(metadata.lastCheckout, now)
    (createdInterval.getMillis > config.maxConnectionAge) || (idleInterval.getMillis > config.maxConnectionIdleTime)
  }

  class ConnectionRequestException(message: String, cause: Throwable) extends Exception(message, cause) {
    def this(message: String) = {
      this(message, null)
    }
  }
  class ConnectionRequestTimeoutException(period: Long)
    extends ConnectionRequestException(s"Connection request timed out, took longer than ${period}ms.")
  class MaxConnectionRequestsExceededException(max: Int)
    extends ConnectionRequestException(s"Max number outstanding requests has been exceeded. Max is $max.")
}
