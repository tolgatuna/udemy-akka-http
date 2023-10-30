package part2_lowlevelserver

import akka.pattern.ask
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.util.Timeout
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import part2_lowlevelserver.GuitarDB.{CreateGuitar, FindAllGuitars, FindAllGuitarsWithStockOption, FindGuitar, GuitarCreated, UpdateInventoryForAGuitar}
import spray.json._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps // STEP 1 For marshalling

case class Guitar(make: String, model: String, quantity: Int = 0)

object GuitarDB {
  case class CreateGuitar(guitar: Guitar)

  case class GuitarCreated(id: Int)

  case class FindGuitar(id: Int)

  case object FindAllGuitars

  case class FindAllGuitarsWithStockOption(isInStock: Boolean)

  case class UpdateInventoryForAGuitar(id: Int, quantity: Int)
}

class GuitarDB extends Actor with ActorLogging {

  import GuitarDB._

  override def receive: Receive = onMessage(0, Map())

  private def onMessage(currentGuitarId: Int, guitars: Map[Int, Guitar]): Receive = {
    case FindAllGuitars =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList
    case FindAllGuitarsWithStockOption(isInStock) =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList.filter(g => if (isInStock) g.quantity > 0 else g.quantity == 0)
    case FindGuitar(id) =>
      log.info(s"Searching for a guitar by id: $id")
      sender() ! guitars.get(id)
    case UpdateInventoryForAGuitar(id, quantity) =>
      val maybeGuitar = guitars.get(id)
      if(maybeGuitar.isDefined) {
        val guitar = maybeGuitar.get
        context.become(onMessage(currentGuitarId, guitars + (id -> Guitar(guitar.make, guitar.model, quantity))))
      }
      sender() ! maybeGuitar
    case CreateGuitar(guitar) =>
      log.info(s"Adding guitar $guitar with id $currentGuitarId")
      sender() ! GuitarCreated(currentGuitarId)
      context.become(onMessage(currentGuitarId + 1, guitars + (currentGuitarId -> guitar)))
  }
}

// STEP 2 For marshalling extend protocol from DefaultJsonProtocol
trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {
  implicit val guitarFormat: RootJsonFormat[Guitar] = jsonFormat3(Guitar) // STEP 3 For marshalling use jsonFormatX -> if there is two parameter in class use 2, if there is 3 use 3
}

object LowLevelRest extends App with GuitarStoreJsonProtocol { // STEP 4 For marshalling, extend from that trait as well!
  implicit val system: ActorSystem = ActorSystem("LowLevelRest")

  import system.dispatcher

  // JSON -> marshalling
  // EXAMPLES
  private val simpleGuitar = Guitar("Fender", "Stratocaster")
  println(simpleGuitar.toJson.prettyPrint)

  // unmarshalling
  val simpleGuitarJsonString =
    """
      |{
      |  "make": "Fender",
      |  "model": "Stratocaster",
      |  "quantity": 10
      |}""".stripMargin
  println(simpleGuitarJsonString.parseJson.convertTo[Guitar])

  /*
    setup
   */
  private val guitarDb = system.actorOf(Props[GuitarDB], "LowLevelGuitarDB")
  private val guitarList = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )
  guitarList.foreach { guitar =>
    guitarDb ! CreateGuitar(guitar)
  }
  /*
    Server code
      GET  on localhost:8080/api/guitar      => ALL the guitars in the store
      GET  on localhost:8080/api/guitar?id=x => Get requested guitar in the store
      POST on localhost:8080/api/guitar      => insert the guitar into the store
   */
  implicit val defaultTimeout: Timeout = Timeout(2 seconds)

  private def getAllGuitars: Future[HttpResponse] = {
    val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitars).mapTo[List[Guitar]]
    guitarsFuture.map { guitars =>
      HttpResponse(
        status = StatusCodes.OK,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          guitars.toJson.prettyPrint
        )
      )
    }
  }

  private def getGuitar(query: Query): Future[HttpResponse] = {
    val guitarId = query.get("id").map(_.toInt)
    guitarId match {
      case None => Future(HttpResponse(StatusCodes.NotFound))
      case Some(id: Int) =>
        val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(id)).mapTo[Option[Guitar]]
        guitarFuture.map {
          case None => HttpResponse(StatusCodes.NotFound)
          case Some(guitar) =>
            HttpResponse(entity = HttpEntity(
              ContentTypes.`application/json`,
              guitar.toJson.prettyPrint
            ))
        }
    }
  }

  private def getGuitarInventory(query: Query): Future[HttpResponse] = {
    val isInStock = query.get("inStock").map(_.toBoolean)
    isInStock match {
      case None => Future(HttpResponse(StatusCodes.NotFound))
      case Some(inStock: Boolean) =>
        val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitarsWithStockOption(inStock)).mapTo[List[Guitar]]
        guitarsFuture.map { guitars =>
          HttpResponse(
            status = StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          )
        }
    }
  }

  private def updateGuitarInventory(query: Query): Future[HttpResponse] = {
    val quantity = query.get("quantity").map(_.toInt)
    val id = query.get("id").map(_.toInt)
    id match {
      case None => Future(HttpResponse(StatusCodes.BadRequest))
      case Some(idVal) =>
        quantity match {
          case None =>
            Future(HttpResponse(StatusCodes.BadRequest))
          case Some(quantityVal) =>
            val guitarFuture = (guitarDb ? UpdateInventoryForAGuitar(idVal, quantityVal)).mapTo[Option[Guitar]]
            guitarFuture.map {
              case None => HttpResponse(StatusCodes.NotFound)
              case Some(guitar) => HttpResponse(StatusCodes.OK)
            }
        }
    }
  }

  private val requestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
      /* Query parameter handling code in here (localhost:8080/api/guitar?id=1)*/
      val query = uri.query()
      if (query.isEmpty) {
        getAllGuitars
      } else {
        // fetch guitar associated to the guitar id
        getGuitar(query)
      }
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      if (query.isEmpty) {
        Future {
          HttpResponse(status = StatusCodes.BadRequest)
        }
      } else {
        // fetch guitar associated to the guitar id
        getGuitarInventory(query)
      }
    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      val query = uri.query()
      if (query.isEmpty) {
        Future {HttpResponse(status = StatusCodes.BadRequest)}
      } else {
        updateGuitarInventory(query)
      }
    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
      // entities are a Source[ByteString]
      val strictEntityFuture = entity.toStrict(3 seconds)
      strictEntityFuture.flatMap { strictEntity =>
        val guitarJsonString = strictEntity.data.utf8String
        val guitar = guitarJsonString.parseJson.convertTo[Guitar]
        val createGuitarFuture: Future[GuitarCreated] = (guitarDb ? CreateGuitar(guitar)).mapTo[GuitarCreated]
        createGuitarFuture.map { guitarCreated =>
          HttpResponse(StatusCodes.OK)
        }
      }
    case request: HttpRequest =>
      request.discardEntityBytes()
      Future {
        HttpResponse(status = StatusCodes.NotFound)
      }
  }


  Http().newServerAt("localhost", 8080).bind(requestHandler)
}
