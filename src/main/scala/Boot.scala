package com.mlh.spraysample

import scala.concurrent.duration._

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.{read, write}
import spray.can.Http
import spray.httpx.Json4sSupport
import spray.routing._
import spray.can.server.Stats
import spray.http.StatusCodes._

object Boot extends App {
  implicit val system = ActorSystem("spray-sample-system")

  /* Use Akka to create our Spray Service */
  val service= system.actorOf(Props[SpraySampleActor], "spray-sample-service")

  /* and bind to Akka's I/O interface */
  IO(Http) ! Http.Bind(service, system.settings.config.getString("app.interface"), system.settings.config.getInt("app.port"))

  /* Allow a user to shutdown the service easily */
  println("Hit any key to exit.")
  val result = readLine()
  system.shutdown()
}

/* Our Server Actor is pretty lightweight; simply mixing in our route trait and logging */
class SpraySampleActor extends Actor with SpraySampleService with ActorLogging {
  def actorRefFactory = context
  def receive = runRoute(spraysampleRoute)
}

/* Used to mix in Spray's Marshalling Support with json4s */
object Json4sProtocol extends Json4sSupport {
  implicit def json4sFormats: Formats = DefaultFormats
}

/* Our case class, used for request and responses */
case class Foo(bar: String)

/* Our route directives, the heart of the service.
 * Note you can mix-in dependencies should you so chose */
trait SpraySampleService extends HttpService {
  import Json4sProtocol._
  import WorkerActor._

  //These implicit values allow us to use futures
  //in this trait.
  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(5 seconds)

  //Our worker Actor handles the work of the request.
  val worker = actorRefFactory.actorOf(Props[WorkerActor], "worker")

  val spraysampleRoute = {
    path("entity") {
      get {
        complete(List(Foo("foo1"), Foo("foo2")))
      } ~
      post {
        respondWithStatus(Created) {
          entity(as[Foo]) { someObject =>
            doCreate(someObject)
          }
        }
      }
    } ~
    path ("entity" / Segment) { id =>
      get {
        complete(s"detail ${id}")
      } ~
      post {
        complete(s"update ${id}")
      }
    } ~
    path("stats") {
      complete {
        //This is another way to use the Akka ask pattern
        //with Spray.
        actorRefFactory.actorSelection("/user/IO-HTTP/listener-0")
          .ask(Http.GetStats)(1.second)
          .mapTo[Stats]
      }
    }
  }

  def doCreate[T](foo: Foo) = {
    //We use the Ask pattern to return
    //a future from our worker Actor,
    //which then gets passed to the complete
    //directive to finish the request.
    val response = (worker ? Create(foo))
                  .mapTo[Ok]
                  .map(result => s"I got a response: ${result}")
                  .recover { case _ => "error" }

    complete(response)
  }

}
