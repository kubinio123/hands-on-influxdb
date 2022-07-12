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

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

object CryptoData extends App {

  implicit val as: ActorSystem = ActorSystem("crypto-data")

  case class Trade(
      exchange: String,
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
                .addField("quote", trade.quote)
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
                    .time(Instant.now(), WritePrecision.MS)
              }.toSeq
            case _ => Seq.empty
          }
      }
      .grouped(1000)
//      .to(Influxdb.write)
      .to(Sink.foreach[Seq[Point]](x => println(x.size)))
      .run()

  val backend = AkkaHttpBackend.usingActorSystem(as)

  val never_ending_story = Future.sequence(
    Seq(
      subscribeTrades.send(backend),
      subscribePrices.send(backend)
    )
  )

  Await.result(never_ending_story, 15.minutes)

  backend.close()
  Influxdb.close()
}
