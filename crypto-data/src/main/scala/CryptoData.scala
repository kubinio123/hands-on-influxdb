import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import cats.syntax.all._
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.write.Point
import io.circe.generic.auto._
import io.circe.parser._
import sttp.capabilities.akka.AkkaStreams
import sttp.client3._
import sttp.client3.akkahttp.AkkaHttpBackend
import sttp.ws.WebSocketFrame

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

object CryptoData extends App {

  case class Trade(
      exchange: String,
      base: String,
      quote: String,
      direction: String,
      volume: Double,
      timestamp: Long
  )

  type Prices = Map[String, Double]

  implicit val as: ActorSystem = ActorSystem("crypto-data")

  val points = new ConcurrentLinkedQueue[Point]()

  val processTrades: AkkaStreams.Pipe[WebSocketFrame.Data[_], WebSocketFrame] =
    Flow[WebSocketFrame.Data[_]]
      .collect { case WebSocketFrame.Text(payload, _, _) => decode[Trade](payload) }
      .collect { case Right(trade) => trade }
      .map { trade =>
        Point
          .measurement("trades")
          .addTag("base", trade.base)
          .addTag("direction", trade.direction)
          .addField("quote", trade.quote)
          .addField("volume", trade.volume)
          .time(trade.timestamp, WritePrecision.MS)
      }
      .via(Flow[Point].map(points.offer))
      .map { _ => WebSocketFrame.pong }

  val processPrices: AkkaStreams.Pipe[WebSocketFrame.Data[_], WebSocketFrame] =
    Flow[WebSocketFrame.Data[_]]
      .collect { case WebSocketFrame.Text(payload, _, _) => decode[Prices](payload) }
      .collect { case Right(prices) => prices }
      .mapConcat { prices =>
        prices.map {
          case (currency, price) =>
            Point
              .measurement("prices")
              .addTag("currency", currency)
              .addField("price", price)
              .time(Instant.now(), WritePrecision.MS)
        }.toSeq
      }
      .via(Flow[Point].map(points.offer))
      .map { _ => WebSocketFrame.pong }

  val subscribeTrades = basicRequest
    .response(asWebSocketStream(AkkaStreams)(processTrades))
    .get(uri"wss://ws.coincap.io/trades/binance")

  val subscribePrices = basicRequest
    .response(asWebSocketStream(AkkaStreams)(processPrices))
    .get(uri"wss://ws.coincap.io/prices?assets=ALL")

  val backend = AkkaHttpBackend.usingActorSystem(as)

  val process = Future {
    Source.single(Seq.fill(1000)(points.poll)).to(Influxdb.write).run()
  }

  Future.sequence(
    Seq(
      subscribeTrades.send(backend),
      subscribePrices.send(backend)
    )
  )

  Await.result(process.foreverM, 15.minutes)

  backend.close()
  Influxdb.close()
}
