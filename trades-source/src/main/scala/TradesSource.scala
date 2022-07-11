import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import cats.syntax.all._
import com.influxdb.LogLevel
import com.influxdb.client.InfluxDBClientOptions
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.scala.InfluxDBClientScalaFactory
import com.influxdb.client.write.Point
import io.circe.generic.auto._
import io.circe.parser._
import sttp.capabilities.akka.AkkaStreams
import sttp.client3._
import sttp.client3.akkahttp.AkkaHttpBackend
import sttp.ws.{WebSocket, WebSocketFrame}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object TradesSource extends App {

  case class Trade(exchange: String, base: String, quote: String, direction: String, volume: Double, timestamp: Long)

  val EmptyFrame = WebSocketFrame.binary(Array.empty)

  val backend = AkkaHttpBackend()

  val influxdbOptions = InfluxDBClientOptions
    .builder()
    .url("http://localhost:8086")
    .org("jc")
    .authenticate("admin", "adminadmin".toCharArray)
    .logLevel(LogLevel.BASIC)
    .build()

  val influxdbClient = InfluxDBClientScalaFactory.create(influxdbOptions)
  val influxdbWrite = influxdbClient.getWriteScalaApi

  val useWebSocketStream: AkkaStreams.Pipe[WebSocketFrame.Data[_], WebSocketFrame] =
    Flow[WebSocketFrame.Data[_]]
      .collect { case WebSocketFrame.Text(payload, _, _) => decode[Trade](payload) }
      .wireTap(println(_))
      .collect {
        case Right(trade) =>
          Point
            .measurement("trade")
            .addTag("base", trade.base)
            .addTag("direction", trade.direction)
            .addField("quote", trade.quote)
            .addField("volume", trade.volume)
            .time(trade.timestamp, WritePrecision.MS)
      }
      .grouped(100)
      .alsoTo(influxdbWrite.writePoints(bucket = "trades".some))
      .map(_ => WebSocketFrame.pong)

  val BatchSize = 100
  implicit val as = ActorSystem("trades-processor")

  def useWebSocket(ws: WebSocket[Future]): Future[Unit] = {

    def process(acc: Seq[Point] = Seq.empty): Future[Unit] = {
      if (acc.size == BatchSize) {
        println(s"Sending batch of ${acc.size} points...")
        Source
          .single(acc)
          .runWith(influxdbWrite.writePoints(bucket = "trades".some))
          .flatMap(_ => process())
      } else {
        ws.receiveText()
          .map(decode[Trade](_))
          .map {
            case Right(trade) =>
              process(
                acc :+ Point
                  .measurement("trade")
                  .addTag("base", trade.base)
                  .addTag("direction", trade.direction)
                  .addField("quote", trade.quote)
                  .addField("volume", trade.volume)
                  .time(trade.timestamp, WritePrecision.MS)
              )
            case _ => process(acc)
          }
      }
    }

    process().foreverM
  }

  influxdbClient.ping

  basicRequest
    .response(asWebSocket(useWebSocket(_)))
    .get(uri"wss://ws.coincap.io/trades/binance")
    .send(backend)
    .onComplete { _ =>
      println("I am done")
      backend.close()
    }
}
