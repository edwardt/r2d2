package xebia.r2d2

import java.sql.Connection

import scala.concurrent.Future

trait ConnectionPool {
  def withConnection[T](block: Connection ⇒ Future[T]): Future[T]
}

