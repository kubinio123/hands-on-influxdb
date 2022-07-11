import Dependencies._

name := "hands-on-influxdb"
version := "0.1"

lazy val commonSettings = Seq(
  scalaVersion := "2.13.8"
)

lazy val root = (project in file(".")).aggregate(tradesSource)

lazy val tradesSource = (project in file("trades-source"))
  .settings(commonSettings)
  .settings(
    name := "trades-source",
    libraryDependencies ++= Seq(Libs.sttp3akka, Libs.akkaStreams, Libs.influxdbClient) ++ Libs.circe
  )
