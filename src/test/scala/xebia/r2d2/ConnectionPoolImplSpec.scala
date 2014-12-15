import akka.actor._
import akka.testkit._

class TestConnectionPoolActor {

  import ConnectionPoolActor._

  val connectionPool = createActorUnderTest()

  "The ConnectionPool" should {
    "request a connection" in {
      connectionPool ! RequestConnection
      expectMsg(ConnectionRequest)
    }

  }

  private def createActorUnderTest(): ActorRef = {
    system.actorOf(ConnectionPoolActor.props(), ConnectionPoolActor.name)
  }
}