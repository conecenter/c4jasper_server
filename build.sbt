name := "jasper-server"

version := "0.1"

scalaVersion := "2.13.0"

// https://mvnrepository.com/artifact/com.lowagie/itext
libraryDependencies += "com.lowagie" % "itext" % "2.1.7"

// https://mvnrepository.com/artifact/net.sf.jasperreports/jasperreports
libraryDependencies += "net.sf.jasperreports" % "jasperreports" % "6.9.0"

// https://mvnrepository.com/artifact/com.typesafe.akka/akka-http
libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.1.9"

// https://mvnrepository.com/artifact/com.typesafe.akka/akka-stream
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.0-M5"
val tethysVersion = "0.10.0"
libraryDependencies ++= Seq(
  "com.tethys-json" %% "tethys-core" % tethysVersion,
  "com.tethys-json" %% "tethys-jackson" % tethysVersion,
  "com.tethys-json" %% "tethys-derivation" % tethysVersion
)

enablePlugins(AssemblyPlugin)

assemblyJarName in assembly := s"${name.value}-v${version.value}.jar"
assemblyOutputPath in assembly := new java.io.File(
  "." + java.io.File.separator + (assemblyJarName in assembly).value
)

assemblyMergeStrategy in assembly := {
  case PathList(xs@_*) if xs.exists(_.contains("jasperreports_extension.properties")) => MergeStrategy.concat
  case x => (assemblyMergeStrategy in assembly).value(x)
}
