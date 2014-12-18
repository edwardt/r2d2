package xebia.r2d2.defaults

import java.sql.Connection
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import xebia.r2d2.{ ConnectionPool, ConnectionPoolActor }

import scala.concurrent.{ ExecutionContext, Future }

trait DefaultConnectionPool extends ConnectionPool {
  override def withConnection[T](block: (Connection) ⇒ Future[T]): Future[T] = DefaultConnectionPool.withConnection(block)
}

object DefaultConnectionPool extends ConnectionPoolActor with DefaultDatabaseConfig with DefaultCurrentTime {
  override def system: ActorSystem = ActorSystem.create("thread-pools")
  override lazy val blockingExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(poolSize))
}
