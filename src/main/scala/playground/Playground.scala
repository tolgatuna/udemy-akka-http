package playground

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

import scala.io.StdIn

object Playground extends App {
  implicit val system: ActorSystem = ActorSystem("AkkaHttpPlayground")
  import system.dispatcher

  private val simpleRoute =
    pathEndOrSingleSlash {
      complete(HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        """
          |<html>
          | <body>
          |   Rock the JVM with Akka HTTP!
          | </body>
          |</html>
        """.stripMargin
      ))
    }

  private val bindingFuture = Http()
    .newServerAt("localhost", 8080)
    .bind(simpleRoute)
  // wait for a new line, then terminate the server
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())

  val l = List(2).flatMap(Seq(_))
}