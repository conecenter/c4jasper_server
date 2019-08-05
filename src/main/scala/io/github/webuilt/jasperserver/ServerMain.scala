package io.github.webuilt.jasperserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import tethys._
import tethys.derivation.auto._
import tethys.jackson._

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn

object ServerMain extends App
{

  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  case class MyHeader(name: String, value: String, renderInRequests: Boolean)
    extends akka.http.scaladsl.model.headers.CustomHeader
  {
    override def renderInResponses(): Boolean = !renderInRequests
  }
  case class C4User(
    srcId: String,
    firstName: String,
    lastName: String,
    username: String,
    role: String,
    company: String,
  )
  val route: Route =
    path("hello") {
      get {
        complete {
          val usersRequest = Http(system)
            .singleRequest(
              HttpRequest(
                method = HttpMethods.POST,
                uri = "https://syncpost.dev.cone.ee/cto-tests-http",
                headers = MyHeader("query", "users", renderInRequests = true) :: Nil
              )
            )
          usersRequest.map {
            response =>
              val userHeader = response.headers.find(_.lowercaseName == "users")
              val jsonParsed = userHeader.flatMap(_.value.jsonAs[Seq[C4User]].toOption)
              val htmlString =
                jsonParsed
                  .toList
                  .flatten
                  .mkString("<h1>howdy</h1><br/><ul><li>", "</li><li>", "</li></ul>")
              HttpEntity(
                ContentTypes.`text/html(UTF-8)`,
                htmlString
              )
          }

        }
      }
    }
  val bindingFuture: Future[Http.ServerBinding] = Http().bindAndHandle(route, "localhost", 8080)
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}
