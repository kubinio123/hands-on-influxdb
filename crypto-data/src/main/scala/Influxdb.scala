import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.influxdb.LogLevel
import com.influxdb.client.write.Point
import com.influxdb.client.{InfluxDBClientFactory, InfluxDBClientOptions}

import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

object Influxdb {

  private val options = InfluxDBClientOptions
    .builder()
    .bucket("crypto")
    .url("http://localhost:8086")
    .org("jc")
    .authenticate("admin", "adminadmin".toCharArray)
    .logLevel(LogLevel.BASIC)
    .build()

  private lazy val client = {
    val client = InfluxDBClientFactory.create(options)
    client.ping()
    client
  }

  private val writeApi = client.makeWriteApi()

  private val batchCounter = new AtomicInteger(0)

  val write: Flow[Seq[Point], Unit, NotUsed] = Flow[Seq[Point]]
    .wireTap { p =>
      val n = batchCounter.incrementAndGet()
      println(s"sending batch $n of ${p.size} points...")
    }
    .map(points => writeApi.writePoints(points.asJava))

  def close(): Unit = client.close()
}
