package io.github.webuilt.jasperserver

import java.io.File
import java.nio.file.Path

import com.typesafe.scalalogging.{LazyLogging, Logger}

import scala.io.Source
import scala.util.Using

trait ImplicitLazyLogging extends LazyLogging
{
  implicit val implicitLogger: Logger = logger
}

object PrettyImplicits
{
  implicit class LoggerInterpolation(val sc: StringContext) extends AnyVal
  {
    def info(args: Any*)(implicit logger: Logger): Unit = logger.info(sc.s(args: _*).capitalize)
    def debug(args: Any*)(implicit logger: Logger): Unit = logger.debug(sc.s(args: _*).capitalize)
  }
  case class Config(map: Map[String, String])
  {
    def get(key: String): Option[String] = map.get(key)
  }
  object Config
  {
    private val splitByEq: PartialFunction[String, (String, String)] =
      new PartialFunction[String, (String, String)]
      {
        override def isDefinedAt(x: String): Boolean = x.split("=").length == 2
        override def apply(v1: String): (String, String) =
          v1.split("=").toList match {
            case key :: value :: Nil =>
              key -> value
          }
      }
    def apply(configFile: File): Config =
      Config(
        map = Using.resource(Source.fromFile(configFile)) {
          _.getLines.collect(splitByEq)
        }.toMap
      )
    def apply(configFilePath: Path): Config = apply(configFilePath.toFile)
    def apply(configFileLocation: String): Config = apply(Path.of(configFileLocation))
    def fromEnv: Config = Config(sys.env)
  }
}