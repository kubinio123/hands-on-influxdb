import akka.Done
import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
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

  val pointsQueue: SourceQueueWithComplete[Point] =
    Source
      .queue[Point](1000, OverflowStrategy.backpressure)
      .grouped(100)
      .wireTap(x => println(x))
      .recover { case e =>
        println(e)
        throw e
      }
      // todo fix StreamDetachedException
      .to(Influxdb.write)
      .run()

  val pointsQueueSink: Sink[Point, Future[Done]] =
    Sink.foreachAsync[Point](10)(pointsQueue.offer(_).void)

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
      .wireTap(p => pointsQueue.offer(p).onComplete(println(_)))
//      .via(Flow[Point].mapAsync(1)(pointsQueue.offer))
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
//      .via(Flow[Point].mapAsync(1)(pointsQueue.offer))
      .wireTap(p => pointsQueue.offer(p).onComplete(println(_)))
      .map { _ => WebSocketFrame.pong }

  val trades = basicRequest
    .response(asWebSocketStream(AkkaStreams)(processTrades))
    .get(uri"wss://ws.coincap.io/trades/binance")

  val prices = basicRequest
    .response(asWebSocketStream(AkkaStreams)(processPrices))
    .get(uri"wss://ws.coincap.io/prices?assets=ALL")

  val backend = AkkaHttpBackend.usingActorSystem(as)

  // let it run for 15 minutes...
  Await.result(Seq(trades, prices).map(_.send(backend)).sequence, 15.minutes)

  backend.close()
  Influxdb.close()
}
