package io.github.webuilt.jasperserver

import java.io.ByteArrayOutputStream
import java.sql.DriverManager

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import io.github.webuilt.sjdbc.MyDriver
import net.sf.jasperreports.engine.export.JRPdfExporter
import net.sf.jasperreports.engine.{JasperCompileManager, JasperFillManager, JasperPrint}
import net.sf.jasperreports.export.{SimpleExporterInput, SimpleOutputStreamExporterOutput}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Try, Using}

object ServerMain extends App with LazyLogging {
  logger.info("starting jasper server app")
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  logger.info("akka prepared")
  val driverCP = "io.github.webuilt.sjdbc.MyDriver"
  val authStringOpt: Option[String] = for {
    authFilePath <- sys.env.get("C4_JRAUTH")
    auth <- Using.resource(scala.io.Source.fromFile(authFilePath))(_.getLines.nextOption())
  } yield auth
  val jdbcUrl = authStringOpt.getOrElse("jdbc:my:url=http://cto-syncpost:1080/cto-tests-http username=cto") //"jdbc:my:url=https://syncpost.dev.cone.ee/cto-tests-http username=cto" "jdbc:my:url=http://cto-syncpost:1080/cto-tests-http username=cto"
  logger.info(s"listening to db with $jdbcUrl")
  def driverInit(): Boolean = Try(MyDriver.getClass).isSuccess
  lazy val conn: java.sql.Connection = if (driverInit()) DriverManager.getConnection(jdbcUrl)
                                       else throw new Exception("couldnt connect to db")
  val jrRoute: Route =
    path("jr") {
      get {
        complete {
          logger.info("received request for report")
          val jpr: JasperPrint = JasperFillManager
            .fillReport(JasperCompileManager.compileReport("./vone.jrxml"), new java.util.HashMap[String, Object](), conn)
          logger.info("report compiled")
          val exporter: JRPdfExporter = new JRPdfExporter()
          exporter.setExporterInput(new SimpleExporterInput(jpr))
          val outs: ByteArrayOutputStream = new java.io.ByteArrayOutputStream()
          exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outs))
          exporter.exportReport()
          val bytes: Array[Byte] = outs.toByteArray
          logger.info("responding with report")
          HttpResponse(entity = HttpEntity(bytes))
        }
      }
    }
  val (interface, port) = "" -> 1080
  val bindingFuture: Future[Http.ServerBinding] = Http().bindAndHandle(jrRoute, interface, port)
  logger.info(s"server running on $interface:$port")
  while (true) ()
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}
