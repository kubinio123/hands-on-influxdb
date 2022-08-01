### Read the blog

This code was created for the purpose of [this blog post](https://softwaremill.com/hands-on-influxdb/), I encourage you to read it first ;)

### Running the example

I assume that `sbt` is installed directly on your OS.

Start InfluxDB instance with `docker compose up`. Organization and credentials are configurable in the `docker-compose.yml`.

Run `CryptoData` application, either via IDEA, or using `sbt "project cryptoData" "run"
` command.

It collects trades and prices data for 1 hour, but you can change this in the code.

### Go to InfluxDB console and have fun

Queries used in the article are in `flux-queries` directory.

