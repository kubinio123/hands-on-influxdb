import akka.Done
import akka.stream.scaladsl.Sink
import com.influxdb.LogLevel
import com.influxdb.client.InfluxDBClientOptions
import com.influxdb.client.scala.InfluxDBClientScalaFactory
import com.influxdb.client.write.Point

import scala.concurrent.Future

object Influxdb {

  private val options = InfluxDBClientOptions
    .builder()
    .bucket("crypto")
    .url("http://localhost:8086")
    .org("jc")
    .authenticate("admin", "adminadmin".toCharArray)
    .logLevel(LogLevel.BASIC)
    .build()

  private val client = InfluxDBClientScalaFactory.create(options)
  private val writeApi = client.getWriteScalaApi

  val write: Sink[Seq[Point], Future[Done]] = writeApi.writePoints()

  def close(): Unit = client.close()
}
