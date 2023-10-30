package part2_lowlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCode, StatusCodes, Uri}
import akka.stream.scaladsl.{Flow, Sink}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import scala.language.postfixOps

object LowLevelAPI extends App {
  implicit val system: ActorSystem = ActorSystem("LowLevelAPI")

  import system.dispatcher

  /*
    Simple demo for see http server running
   */
  private val serverSource = Http().newServerAt("localhost", 8000).connectionSource()
  private val connectionSink = Sink.foreach[IncomingConnection] { connection =>
    println(s"Accepted incoming connection from: ${connection.remoteAddress}")
  }

  private val serverBingingFuture: Future[Http.ServerBinding] = serverSource.to(connectionSink).run()
  serverBingingFuture.onComplete {
    case Success(binding) =>
      println("Server binding successful")
    //      binding.terminate(2 seconds)
    case Failure(ex) => println(s"Server binding failure: $ex")
  }

  // ---------------------------------- 0 ----------------------------------
  /*
    Method 1: synchronously serve HTTP responses
   */
  private val syncRequestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, _, _, _, _) =>
      HttpResponse(
        StatusCodes.OK, // Http 200
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Hello from Akka HTTP with Sync Handle!
            | </body>
            |</html>""".stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound, // 404
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Opps! Not found!
            | </body>
            |</html>""".stripMargin
        )
      )
  }

//  private val httpSyncConnectionHandler = Sink.foreach[IncomingConnection] { connection =>
//    connection.handleWithSyncHandler(syncRequestHandler)
//  }
//  Http().newServerAt("localhost", 8080).connectionSource().runWith(httpSyncConnectionHandler)
  Http().newServerAt("localhost", 8080).bindSync(syncRequestHandler)

  // ---------------------------------- 0 ----------------------------------
  /*
    Method 2: Serve back HTTP response ASYNCHRONOUSLY
   */
  private val asyncRequestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      Future(
        HttpResponse(
          StatusCodes.OK, // Http 200
          entity = HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            """
              |<html>
              | <body>
              |   Hello from Akka HTTP Home with Asyn Handle!!
              | </body>
              |</html>""".stripMargin
          )
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      Future(HttpResponse(
        StatusCodes.NotFound, // 404
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Opps! Not found!
            | </body>
            |</html>""".stripMargin
        )
      ))
  }

  // MANUAL Configuration and creation objects
  //  private val httpAsyncConnectionHandler = Sink.foreach[IncomingConnection] { connection =>
  //    connection.handleWithAsyncHandler(asyncRequestHandler)
  //  }
  //  Http().newServerAt("localhost", 8081).connectionSource().runWith(httpAsyncConnectionHandler)

  // Shorted version
  Http().newServerAt("localhost", 8081).bind(asyncRequestHandler)


  // ---------------------------------- 0 ----------------------------------
  /*
    Method 3: async via Akka Streams
   */
  private val streamBasedRequestHandler: Flow[HttpRequest, HttpResponse, _] = Flow[HttpRequest].map {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      HttpResponse(
        StatusCodes.OK, // Http 200
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Hello from Akka HTTP Home with Stream Handle!!
            | </body>
            |</html>""".stripMargin
        )
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound, // 404
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Opps! Not found!
            | </body>
            |</html>""".stripMargin
        )
      )
  }

  // Manual handle
//  Http().newServerAt("localhost", 8082).connectionSource()
//    .runForeach(connection => {
//      connection.handleWith(streamBasedRequestHandler)
//    })
  // Short version
  Http().newServerAt("localhost", 8082).bindFlow(streamBasedRequestHandler)

  /**
   * Exercise: create your own Http Server running on localhost on 8388, which replies
   *  - with a welcome message on the "front door" localhost:8388
   *  - with a proper HTML on localhost 8388/about
   *  - with a 404 message otherwise
   */

  private val streamBasedRequestHandlerForExercise: Flow[HttpRequest, HttpResponse, _] = Flow[HttpRequest].map {
    case HttpRequest(HttpMethods.GET, Uri.Path("/about"), _, _, _) =>
      HttpResponse(
        StatusCodes.OK, // Http 200
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   This is an about page
            | </body>
            |</html>""".stripMargin
        )
      )
    case HttpRequest(HttpMethods.GET, Uri.Path("/"), _, _, _) =>
      HttpResponse(
        StatusCodes.OK, // Http 200
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   This is the home page
            | </body>
            |</html>""".stripMargin
        )
      )
      // Lets say /search redirects to some other part of our website/webapp/microservice... whatever...
    case HttpRequest(HttpMethods.GET, Uri.Path("/search"), _, _, _) =>
      HttpResponse(
        StatusCodes.Found,
        headers = List(Location("http://google.com"))
      )
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(
        StatusCodes.NotFound, // 404
        entity = HttpEntity(
          ContentTypes.`text/html(UTF-8)`,
          """
            |<html>
            | <body>
            |   Opps! Not found!
            | </body>
            |</html>""".stripMargin
        )
      )
  }

  private val bindingFuture: Future[Http.ServerBinding] = Http().newServerAt("localhost", 8388).bindFlow(streamBasedRequestHandlerForExercise)
  // Shutting Actor system
//  bindingFuture.flatMap(binding => binding.unbind())
//    .onComplete(_ => system.terminate())


}
