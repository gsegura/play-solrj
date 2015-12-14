import sbt._
import Keys._
import play.PlayImport._

object ApplicationBuild extends Build {

  val appName         = "play-solrj"
  val appVersion      = "0.6-SNAPSHOT"

  val main = Project(appName, file(".")).enablePlugins(play.PlayScala).settings(
    version := appVersion,
    libraryDependencies ++= Seq(
      ws,
      "org.apache.solr" % "solr-solrj" % "4.8.1",
      "org.apache.solr" % "solr-test-framework" % "4.8.1" % "test",
      "org.mockito" % "mockito-all" % "1.9.5" % "test"
    ),
    scalacOptions ++= Seq("-feature"),
    scalaVersion := "2.11.1",
    resolvers += "Restlet repository" at "http://maven.restlet.org/",
    organization  := "org.opencommercesearch",
    publishMavenStyle := true,
    publishTo <<= version { version: String =>
      val baseUrl = "http://oss.jfrog.org/artifactory/"
      val (name, url) = if (version.contains("-SNAPSHOT"))
        ("oss-snapshots-pub", baseUrl + "oss-snapshot-local/")
      else
        ("oss-pub", baseUrl + "oss-release-local/")
      Some(Resolver.url(name, new URL(url))(Resolver.mavenStylePatterns))
    }
  )

}
