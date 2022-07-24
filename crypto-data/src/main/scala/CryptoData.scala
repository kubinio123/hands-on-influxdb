import akka.actor.ActorSystem
import akka.stream.BoundedSourceQueue
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.write.Point
import io.circe.generic.auto._
import io.circe.parser._
import sttp.capabilities.akka.AkkaStreams
import sttp.client3._
import sttp.client3.akkahttp.AkkaHttpBackend
import sttp.ws.WebSocketFrame

import java.time.Instant.now
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

object CryptoData extends App {

  implicit val as: ActorSystem = ActorSystem("crypto-data")

  case class Trade(
      base: String,
      quote: String,
      direction: String,
      volume: Double,
      timestamp: Long
  )

  type Prices = Map[String, Double]

  case class Frame(subscription: String, payload: String)

  val subscribeTrades = basicRequest
    .response(asWebSocketStream(AkkaStreams)(processFrame(subscription = "trades")))
    .get(uri"wss://ws.coincap.io/trades/binance")

  val subscribePrices = basicRequest
    .response(asWebSocketStream(AkkaStreams)(processFrame(subscription = "prices")))
    .get(uri"wss://ws.coincap.io/prices?assets=ALL")

  val influxdb = new Influxdb

  def processFrame(subscription: String): AkkaStreams.Pipe[WebSocketFrame.Data[_], WebSocketFrame] =
    Flow.fromFunction {
      case WebSocketFrame.Text(payload, _, _) =>
        framesQueue.offer(Frame(subscription, payload))
        WebSocketFrame.pong
      case _ => WebSocketFrame.pong
    }

  val framesQueue: BoundedSourceQueue[Frame] =
    Source
      .queue[Frame](10000)
      .mapConcat {
        case Frame("trades", payload) =>
          decode[Trade](payload) match {
            case Right(trade) =>
              val point = Point
                .measurement("trades")
                .addTag("base", trade.base)
                .addTag("direction", trade.direction)
                .addTag("quote", trade.quote)
                .addField("volume", trade.volume)
                .time(trade.timestamp, WritePrecision.MS)

              Seq(point)
            case _ => Seq.empty
          }
        case Frame("prices", payload) =>
          decode[Prices](payload) match {
            case Right(prices) =>
              prices.map {
                case (currency, price) =>
                  Point
                    .measurement("prices")
                    .addTag("currency", currency)
                    .addField("price", price)
                    .time(now(), WritePrecision.MS)
              }.toSeq
            case _ => Seq.empty
          }
        case _ => Seq.empty
      }
      .grouped(1000)
      .via(influxdb.write)
      .to(Sink.ignore)
      .run()

  val backend = AkkaHttpBackend.usingActorSystem(as)

  try {
    val subscriptions = Future.sequence(
      Seq(
        subscribeTrades.send(backend),
        subscribePrices.send(backend)
      )
    )

    Await.result(subscriptions, 1.hour)
  } catch {
    case _: Throwable =>
      backend.close()
      influxdb.close()
  }
}
