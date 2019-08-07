package io.github.webuilt.jasperserver

import java.io.ByteArrayOutputStream
import java.sql.DriverManager

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import io.github.webuilt.jasperserver.PrettyImplicits._
import io.github.webuilt.sjdbc.MyDriver
import net.sf.jasperreports.engine.export.JRPdfExporter
import net.sf.jasperreports.engine.{JasperCompileManager, JasperFillManager, JasperPrint}
import net.sf.jasperreports.export.{SimpleExporterInput, SimpleOutputStreamExporterOutput}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

object ServerMain extends App with ImplicitLazyLogging
{
  info"Starting Jasper Server App"
  debug"Preparing Akka ecosystem"
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  debug"Akka is prepared"
  val config = Config.fromEnv
  val driverCP = config.get("driver").getOrElse("io.github.webuilt.sjdbc.MyDriver")
  val dbUrl = config.get("dburl")
    .getOrElse("jdbc:my:url=https://syncpost.dev.cone.ee/cto-tests-http username=cto")
  debug"loaded DB configuration: [$dbUrl] with driver: $driverCP"
  lazy val driverInit: Boolean = Try(MyDriver.getClass).isSuccess
  info"driver$driverCP is ${if (driverInit) "" else "not "}initialized"
  lazy val conn: java.sql.Connection = if (driverInit) DriverManager.getConnection(dbUrl)
  else throw new Exception("couldnt connect to db")
  info"connected to $dbUrl"
  val jrRoute: Route =
    path("jr") {
      get {
        complete {
          info"request for report received"
          val jpr: JasperPrint = JasperFillManager
            .fillReport(JasperCompileManager.compileReport("./vone.jrxml"),
              new java.util.HashMap[String, Object](), conn)
          info"report compiled and filled"
          val exporter: JRPdfExporter = new JRPdfExporter()
          exporter.setExporterInput(new SimpleExporterInput(jpr))
          val outs: ByteArrayOutputStream = new java.io.ByteArrayOutputStream()
          exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outs))
          exporter.exportReport()
          val bytes: Array[Byte] = outs.toByteArray
          info"sending completed report back"
          HttpResponse(entity = HttpEntity(bytes))
        }
      }
    }
  val (interface, port) = "0.0.0.0" -> 1080
  val bindingFuture: Future[Http.ServerBinding] = Http().bindAndHandle(jrRoute, interface, port)
  info"successfully binded port $port\nwaiting to requests"
  while (true) () //todo stop possibility
  /*bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done*/
}
