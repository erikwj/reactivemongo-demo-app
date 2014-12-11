import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._

object ApplicationBuild extends Build {

  val appName = "mongo-app"
  val appVersion = "1.0-SNAPSHOT"

  val appDependencies = Seq(
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.5.0.akka23",
    "org.webjars" % "requirejs" % "2.1.14-1",
    "org.webjars" % "underscorejs" % "1.6.0-3",
    "org.webjars" % "jquery" % "1.11.1",
    "org.webjars" % "bootstrap" % "3.1.1-2" exclude("org.webjars", "jquery"),
    "org.webjars" % "angularjs" % "1.2.18" exclude("org.webjars", "jquery"),
    "com.faqtfinding" %% "commandlinetools" % "0.1-SNAPSHOT")

  val main = Project(appName, file(".")).enablePlugins(play.PlayScala).settings(
    resolvers +=  Resolver.sonatypeRepo("releases"),
    version := appVersion,
    scalaVersion := "2.11.4",
    libraryDependencies ++= appDependencies
  )

}

