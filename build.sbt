import Dependencies._

name := "hands-on-influxdb"
version := "0.1"

lazy val commonSettings = Seq(
  scalaVersion := "2.13.8"
)

lazy val root = (project in file(".")).aggregate(cryptoData)

lazy val cryptoData = (project in file("crypto-data"))
  .settings(commonSettings)
  .settings(
    name := "crypto-data",
    libraryDependencies ++= Seq(Libs.sttp3akka, Libs.akkaStreams, Libs.influxdbClient) ++ Libs.circe
  )
