import sbt._

object Dependencies {

  object V {
    val sttp3 = "3.6.2"
    val akkaStreams = "2.6.19"
    val influxdbClient = "6.3.0"
    val circe = "0.14.2"
  }

  object Libs {
    val sttp3akka = "com.softwaremill.sttp.client3" %% "akka-http-backend" % V.sttp3
    val sttp3circe = "com.softwaremill.sttp.client3" %% "circe" % V.sttp3
    val akka: Seq[ModuleID] = Seq("akka-stream", "akka-actor-typed", "akka-slf4j").map(lib => "com.typesafe.akka" %% lib % V.akkaStreams)
    val influxdbClient = "com.influxdb" % "influxdb-client-java" % V.influxdbClient
    val circe: Seq[ModuleID] = Seq("circe-core", "circe-generic", "circe-parser").map(lib => "io.circe" %% lib % V.circe)
  }
}
