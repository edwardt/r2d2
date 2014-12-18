package xebia.r2d2

import scala.concurrent.ExecutionContext

trait BlockingExecutionContext {
  def blockingExecutionContext: ExecutionContext
}
