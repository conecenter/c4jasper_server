package io.github.webuilt.jasperserver

import java.io.{ByteArrayOutputStream, DataOutputStream, File, FileOutputStream, ObjectOutputStream, PrintWriter}
import java.nio.file.{Files, Paths}
import java.sql.DriverManager
import java.util
import java.util.Properties

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RequestContext, Route}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import io.github.webuilt.jasperserver.PrettyImplicits._
import io.github.webuilt.sjdbc.MyDriver
import net.sf.jasperreports.engine.export.JRPdfExporter
import net.sf.jasperreports.engine.util.JRSaver
import net.sf.jasperreports.engine.{JasperCompileManager, JasperFillManager, JasperPrint, JasperReport}
import net.sf.jasperreports.export.{SimpleExporterInput, SimpleOutputStreamExporterOutput}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.StdIn
import scala.util.{Try, Using}

object ServerMain extends App with ImplicitLazyLogging {
  // regexes
  val jrxmlR = """(.*)\.jrxml""".r
  val pdfR = """(.*)\.pdf""".r
  val dbUrlExtractR = """jdbc:my:url=(.*)""".r
  // akka
  info"Starting Jasper Server App"
  debug"Preparing Akka ecosystem"
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val actorMaterializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  debug"Akka is prepared"
  val config = Config.fromEnv
  val driverCP = "io.github.webuilt.sjdbc.MyDriver"
  val dbUrlRaw = config
    .get("C4SPJR")
    .orElse(config.get("C4_SPJR"))
    .get
  val dbUrlExtractR(hostUrlRaw) = dbUrlRaw
  val hostUrl: String = if (hostUrlRaw.lastOption.contains('/'))
                          hostUrlRaw.init
                        else hostUrlRaw
  val dbUrl = s"jdbc:my:url=$hostUrl/jasper-data-req"
  debug"loaded DB configuration: [$dbUrl] with driver: $driverCP"
  lazy val driverInit: Boolean = Try(MyDriver.getClass).isSuccess
  info"driver$driverCP is ${
    if (driverInit) ""
    else "not "
  }initialized"
  def conn(
    username: String,
    props: java.util.Properties = new java.util.Properties(),
  ): java.sql.Connection = if (driverInit) DriverManager.getConnection(s"$dbUrl user=$username", props)
                           else throw new Exception("couldn't connect to db")
  info"connected to $dbUrl"
  val reportSyncRoute: Route = pathPrefix("report-sync") {
    path(pdfR) {
      repName: String  =>
        post {
          request: RequestContext =>
            request.complete {
              info"sync request for report of type $repName received"
              val response = {
                val reportParameters: util.HashMap[String, Object] = new java.util.HashMap[String, Object]()
                val connectionProperties: Properties = new java.util.Properties()
                //todo adequate split
                request.request.headers
                  .filterNot(_.name.equalsIgnoreCase("user"))
                  .toList.foreach { header =>
                  reportParameters.put(header.name, header.value)
                }
                request.request.headers
                  .filter(_.name.equalsIgnoreCase("user"))
                  .toList.foreach { header =>
                  connectionProperties.put(header.name, header.value)
                }
                val username = request.request.headers.find(_.name.equalsIgnoreCase("user")).map(_.value).getOrElse("")
                val jpr: JasperPrint =
                  JasperFillManager.fillReport(
                    JasperCompileManager.compileReport(s"./reports/$repName.jrxml"),
                    reportParameters,
                    conn(username = username, connectionProperties)
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
              HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(response)
              )
            }
        }
    }
  }
  val reportRoute: Route =
    pathPrefix("report") {
      path(pdfR) {
        repName: String =>
          post {
            request: RequestContext =>
              request.complete {
                info"request for report of type $repName received"
                val response = {
                  val reportParameters: util.HashMap[String, Object] = new java.util.HashMap[String, Object]()
                  val connectionProperties: Properties = new java.util.Properties()
                  //todo adequate split
                  request.request.headers
                    .filterNot(_.name.equalsIgnoreCase("user"))
                    .toList.foreach { header =>
                    reportParameters.put(header.name, header.value)
                  }
                  request.request.headers
                    .filter(_.name.equalsIgnoreCase("user"))
                    .toList.foreach { header =>
                    connectionProperties.put(header.name, header.value)
                  }
                  val username = request.request.headers.find(_.name.equalsIgnoreCase("user")).map(_.value).getOrElse("")
                  val jpr: JasperPrint =
                    JasperFillManager.fillReport(
                      JasperCompileManager.compileReport(s"./reports/$repName.jrxml"),
                      reportParameters,
                      conn(username = username, connectionProperties)
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
                Http(system).singleRequest(HttpRequest(
                  method = HttpMethods.POST,
                  uri = hostUrl + "/jasper-report",
                  headers = request.request.headers.find(_.name.equalsIgnoreCase("replyid")).toList,
                  entity = HttpEntity(response),
                )
                )
                HttpResponse(status = StatusCodes.OK,
                  entity = HttpEntity(response)
                )
              }
          }
      }
    }
  val templateRoute: Route =
    pathPrefix("templates") {
      concat(
        pathEndOrSingleSlash {
          post {
            request: RequestContext =>
              info"templates list request received"
              val d = new File("./reports")
              val fileList =
                if (d.exists && d.isDirectory)
                  d.listFiles.filter(_.isFile).toList
                else
                  List[File]()
              info"sending response to templates list to ${hostUrl + "/jasper-templates-list"}"
              Http().singleRequest(
                HttpRequest(
                  HttpMethods.POST,
                  hostUrl + "/jasper-templates-list",
                  request.request.headers.find(_.name.equalsIgnoreCase("replyid")).toList,
                  HttpEntity(fileList.filter(_.getName.endsWith(".jrxml")).map(f => s"filename=${f.getName}&modified=${f.lastModified}").mkString("\n").getBytes("UTF-8"))
                )
              )
              info"response sent"
              request.complete(
                HttpResponse(
                  status = StatusCodes.OK,
                )
              )
          }
        },
        path(jrxmlR) {
          reportName: String =>
            post {
              request: RequestContext =>
                request.complete {
                  info"request for template received for $reportName"
                  val filePath = Paths.get(s"./reports/$reportName.jrxml")
                  val fileContent = Files.readAllBytes(filePath)
                  info"file $reportName found"
                  Http().singleRequest(HttpRequest(
                    method = HttpMethods.POST,
                    uri = hostUrl + "/jasper-template",
                    headers = request.request.headers.find(_.name.toLowerCase == "replyid").toList,
                    entity = HttpEntity(fileContent),
                  )
                  )
                  info"successfully replied"
                  HttpResponse(status = StatusCodes.OK,
                    entity = HttpEntity(fileContent)
                  )
                }
            }
        },
        pathPrefix("upload") {
          path(jrxmlR) {
            reportName: String =>
              post {
                request: RequestContext =>
                  info"new report $reportName storing..."
                  for {
                    body <- Unmarshal(request.request.entity).to[String]
                    compl <- request.complete {
                      Using.resource(new PrintWriter(new File(s"./reports/$reportName.jrxml"))) {
                        _.write(body)
                      }
                      val comp1 = Try(JasperCompileManager.compileReportToFile(s"./reports/$reportName.jrxml"))
                      val comp2 = Try(JasperCompileManager.compileReportToFile(s"./reports/$reportName.jrxml", s"./reports/$reportName.jasper"))
                      val d = new File("./reports")
                      val fileList =
                        if (d.exists && d.isDirectory)
                          d.listFiles.filter(_.isFile).toList
                        else
                          List[File]()
                      fileList.foreach(println)
                      println(comp1)
                      println(comp2)
                      /*val compiledReport: JasperReport = JasperCompileManager.compileReport(s"./reports/$reportName.jrxml")
                      val f1 = new File(s"./reports/$reportName.jasper")
                      f1.setReadable(true)
                      f1.setWritable(true)
                      val f2 = new File(s"./$reportName.jasper")
                      f2.setReadable(true)
                      f2.setWritable(true)
                      Using.resources(new ObjectOutputStream(new FileOutputStream(f1)), new ObjectOutputStream(new FileOutputStream(f2))) {
                        (p1, p2) =>
                          p1.writeObject(compiledReport)
                          p2.writeObject(compiledReport)
                      }*/
                      HttpResponse(status = StatusCodes.Created)
                    }
                    _ = info"new report stored"
                  } yield compl
              }
          }
        }
      )
    }
  val resourceRoute: Route = pathPrefix("resource") {
    path(".*".r) {
      filename =>
      get{
        withoutSizeLimit {
          extractDataBytes { bytes =>
            val finishedWriting =
              bytes.runWith(FileIO.toPath(new File({
                if (filename.startsWith("cheat"))
                  s"./reports/${filename.replaceFirst("cheat", "")}"
                else
                  s"./resources/$filename"
              }
              ).toPath
              )
              )
            onComplete(finishedWriting) { _ =>
              complete(HttpResponse(status = StatusCodes.OK))
            }
          }
        }
      }
    }
  }
  val (interface, port) = "0.0.0.0" -> 1080
  val bindingFuture: Future[Http.ServerBinding] = Http().bindAndHandle(
    reportRoute ~ templateRoute ~ resourceRoute ~ reportSyncRoute,
    interface, port
  )
  info"successfully binded port $port\nwaiting to requests"
  while (true) ()
  info"unbinding"
  //  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => system.terminate()) // and shutdown when done
}
