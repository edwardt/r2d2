package xebia.r2d2

import akka.actor.{ ActorContext, ActorRef, Props }
import scala.concurrent.ExecutionContext
import akka.util.Timeout
import akka.actor.Actor
import scala.concurrent.duration._
import akka.actor.ActorSystem

trait CreationSupport {
  def getChild(name: String): Option[ActorRef]
  def createChild(props: Props, name: String): ActorRef
  def getOrCreateChild(props: Props, name: String): ActorRef = getChild(name).getOrElse(createChild(props, name))
}

trait ActorContextCreationSupport extends CreationSupport {
  def context: ActorContext

  def getChild(name: String): Option[ActorRef] = context.child(name)
  def createChild(props: Props, name: String): ActorRef = context.actorOf(props, name)
}

//======================= ExecutionContextSupport=========================
trait ExecutionContextSupport {
  import scala.concurrent.ExecutionContext
  implicit def executionContext: ExecutionContext
}
trait ActorExecutionContextSupport extends ExecutionContextSupport {
  this: Actor ⇒
  implicit def executionContext: ExecutionContext = context.dispatcher
}

trait ActorSystemContextSupport extends ExecutionContextSupport {
  def system: ActorSystem
  implicit def executionContext: ExecutionContext = system.dispatcher
}

//======================= AskSupport=========================
trait ActorAskSupport {
  implicit val AskTimeout = Timeout(5 seconds)
}
