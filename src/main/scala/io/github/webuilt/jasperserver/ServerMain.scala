package io.github.webuilt.jasperserver

import java.io.{ByteArrayOutputStream, File, PrintWriter}
import java.nio.file.{Files, Paths}
import java.sql.DriverManager

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RequestContext, Route}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import io.github.webuilt.jasperserver.PrettyImplicits._
import io.github.webuilt.sjdbc.MyDriver
import net.sf.jasperreports.engine.export.JRPdfExporter
import net.sf.jasperreports.engine.{JasperCompileManager, JasperFillManager, JasperPrint}
import net.sf.jasperreports.export.{SimpleExporterInput, SimpleOutputStreamExporterOutput}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.util.{Try, Using}

object ServerMain extends App with ImplicitLazyLogging {
  info"Starting Jasper Server App"
  debug"Preparing Akka ecosystem"
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  debug"Akka is prepared"
  val config = Config.fromEnv
  val driverCP = config.get("driver").getOrElse("io.github.webuilt.sjdbc.MyDriver")
  val dbUrl = config.get("dburl")
    .getOrElse("jdbc:my:url=https://syncpost.dev.cone.ee/cto-tests-http")
  debug"loaded DB configuration: [$dbUrl] with driver: $driverCP"
  lazy val driverInit: Boolean = Try(MyDriver.getClass).isSuccess
  info"driver$driverCP is ${
    if (driverInit) ""
    else "not "
  }initialized"
  def conn(
    props: java.util.Properties = new java.util.Properties()
  ): java.sql.Connection = if (driverInit) DriverManager.getConnection(dbUrl, props)
                           else throw new Exception("couldnt connect to db")
  info"connected to $dbUrl"
  val reportRegex = """([\w\d_-]*)\.pdf""".r
  val reportRegexInner = """(.*)\.pdf\?(.*)""".r
  val reportRoute: Route =
    pathPrefix("report") {
      concat(
        path(reportRegex) {
          reportName =>

            parameterMap {
              pMap =>
                get {
                  request: RequestContext =>
                    request.complete {
                      info"request for report received"
                      val response = {
                        val paramsMap = new java.util.HashMap[String, Object]()
                        val properties = new java.util.Properties()
                        pMap.toList.foreach {
                          case (k, v) =>
                            paramsMap.put(k, v)
                            properties.put(k, v)
                        }
                        properties.forEach((t: Any, u: Any) => println(t.toString -> u.toString))

                        val jpr: JasperPrint = JasperFillManager
                          .fillReport(JasperCompileManager.compileReport(s"./reports/$reportName.jrxml"),
                            paramsMap, conn(properties)
                          )
                        info"report compiled and filled"
                        val exporter: JRPdfExporter = new JRPdfExporter()
                        exporter.setExporterInput(new SimpleExporterInput(jpr))
                        val outs: ByteArrayOutputStream = new java.io.ByteArrayOutputStream()
                        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(outs))
                        exporter.exportReport()
                        outs.toByteArray
                      }
                      info"sending completed report back"
                      HttpResponse(status = StatusCodes.OK,
                        entity = HttpEntity(response)
                      )
                    }
                }
            }
        },
        pathEndOrSingleSlash {
          get {
            complete {
              val d = new File("./reports")
              val fileList =
                if (d.exists && d.isDirectory)
                  d.listFiles.filter(_.isFile).toList
                else
                  List[File]()

              HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(fileList.map(_.getName).mkString("\n").getBytes("UTF-8"))
              )
            }
          }
        }
      )
    }
  val jrRoute: Route =
    path("jr.pdf") {
      get {
        complete {
          info"request for report received"
          val jpr: JasperPrint = JasperFillManager
            .fillReport(JasperCompileManager.compileReport("./vone.jrxml"),
              new java.util.HashMap[String, Object](), conn()
            )
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
  val storeReportRegex = """([\w\d_\-]*.jrxml)""".r
  val storeReportRoute: Route =
    pathPrefix("store") {
      path(storeReportRegex) {
        reportName =>
          post {
            req =>
              info"new report $reportName storing..."
              for {
                body <- Unmarshal(req.request.entity).to[String]
                compl <- req.complete {
                  Using.resource(new PrintWriter(new File(s"./reports/$reportName"))) {
                    _.write(body)
                  }
                  HttpResponse(status = StatusCodes.Created)
                }
                _ = info"new report stored"
              } yield compl
          }
      }
    }
  val reportsListRoute: Route =
    pathPrefix("list") {
      concat(
        pathEndOrSingleSlash {
          post {
            complete {
              val d = new File("./reports")
              val fileList =
                if (d.exists && d.isDirectory)
                  d.listFiles.filter(_.isFile).toList
                else
                  List[File]()

              HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(fileList.map(_.getName).mkString("\n").getBytes("UTF-8"))
              )
            }
          }
        },
        path(storeReportRegex) {
          reportName =>
            post {
              complete {
                val filePath = Paths.get(s"./reports/$reportName")
                val exists = Files.exists(filePath)
                lazy val dateCreated = Files.getLastModifiedTime(filePath)
                val responseString =
                  if (exists)
                    s"report $reportName exists and created at ${dateCreated.toString}"
                  else
                    s"report $reportName does not exists on server"
                HttpResponse(
                  status = StatusCodes.OK,
                  entity = HttpEntity(responseString.getBytes("UTF-8"))
                )
              }
            }
        },
        path("""(.*)""".r) {
          _ =>
            post {
              complete {
                HttpResponse(
                  status = StatusCodes.BadRequest
                )
              }
            }
        }
      )
    }
  val (interface, port) = "0.0.0.0" -> 1080
  val bindingFuture: Future[Http.ServerBinding] = Http().bindAndHandle(jrRoute ~ storeReportRoute ~ reportsListRoute ~ reportRoute, interface, port)
  info"successfully binded port $port\nwaiting to requests"
  // while (true) () //todo stop possibility
  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}
