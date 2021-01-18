lazy val sparkVersion = if(System.getenv("SPARK_VERSION")==null) "3.0.1" else System.getenv("SPARK_VERSION")
lazy val luceneVersion = if(System.getenv("LUCENE_VERSION")==null) "8.7.0" else System.getenv("LUCENE_VERSION")
lazy val root = (project in file("."))
  .settings(
    name := "sparkly",
    scalaVersion := "2.12.12",
    retrieveManaged := true,
    version := "1.0.2",
    libraryDependencies += "org.apache.spark" %% "spark-core" % sparkVersion,
    libraryDependencies += "org.apache.spark" %% "spark-sql" % sparkVersion, 
    libraryDependencies += "org.apache.spark" %% "spark-mllib" % sparkVersion,
    libraryDependencies += "org.apache.lucene" % "lucene-core" % luceneVersion, 
    libraryDependencies += "org.apache.lucene" % "lucene-queryparser" % luceneVersion,
    libraryDependencies += "org.apache.lucene" % "lucene-analyzers-common" % luceneVersion, 
    libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.5",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    libraryDependencies += "com.github.fommil.netlib" % "all" % "1.1.2" pomOnly(),
    libraryDependencies += "org.apache.httpcomponents" % "httpmime" % "4.5.6" ,
    scalacOptions ++= Seq("-deprecation", "-feature"),
    assemblyMergeStrategy in assembly := {
      case PathList("org","aopalliance", xs @ _*) => MergeStrategy.last
      case PathList("javax", "inject", xs @ _*) => MergeStrategy.last
      case PathList("javax", "servlet", xs @ _*) => MergeStrategy.last
      case PathList("javax", "activation", xs @ _*) => MergeStrategy.last
      case PathList("javax", "xml", xs @ _*) => MergeStrategy.last
      case PathList("javax", "ws", xs @ _*) => MergeStrategy.last
      case PathList("org", "zuinnote", xs @ _*) => MergeStrategy.last
      case PathList("schemaorg_apache_xmlbeans", "system", xs @ _*) => MergeStrategy.last
      case PathList("org", "apache", xs @ _*) => MergeStrategy.last
      case PathList("com", "google", xs @ _*) => MergeStrategy.last
      case PathList("com", "esotericsoftware", xs @ _*) => MergeStrategy.last
      case PathList("com", "codahale", xs @ _*) => MergeStrategy.last
      case PathList("com", "yammer", xs @ _*) => MergeStrategy.last
      case PathList("org", "w3c", xs @ _*) => MergeStrategy.last
      case PathList("org", "w3", xs @ _*) => MergeStrategy.last
      case PathList("org", "slf4j", xs @ _*) => MergeStrategy.last
      case PathList("org", "openxmlformats", xs @ _*) => MergeStrategy.last
      case PathList("org", "etsi", xs @ _*) => MergeStrategy.last
      case PathList("org", "codehaus", xs @ _*) => MergeStrategy.last
      case PathList("org", "bouncycastle", xs @ _*) => MergeStrategy.last
      case PathList("com", "microsoft", xs @ _*) => MergeStrategy.last
      case PathList("com", "sun", xs @ _*) => MergeStrategy.last
      case PathList("com", "ctc", xs @ _*) => MergeStrategy.last
      case PathList("com", "graphbuilder", xs @ _*) => MergeStrategy.last
      case "about.html" => MergeStrategy.rename
      case "overview.html" => MergeStrategy.rename
      case "META-INF/ECLIPSEF.RSA" => MergeStrategy.last
      case "META-INF/mailcap" => MergeStrategy.last
      case "META-INF/mimetypes.default" => MergeStrategy.last
      case "plugin.properties" => MergeStrategy.last
      case "log4j.properties" => MergeStrategy.last
      case "git.properties" => MergeStrategy.last
      case x =>
        val oldStrategy = (assemblyMergeStrategy in assembly).value
        oldStrategy(x)
    }
  )

 ThisBuild / organization := "com.github.epiconcept-paris"
 ThisBuild / organizationName := "Epiconcept"
 ThisBuild / organizationHomepage := Some(url("http://epiconcept.fr"))

 ThisBuild / scmInfo := Some(
 ScmInfo(
 url("https://github.com/Epiconcept-Paris/sparkly"),
 "scm:git@github.com:Epiconcept-Paris/sparkly.git"
  )
 )


 ThisBuild / developers := List(
    Developer(
    id    = "forchard",
    name  = "Francisco Orchard",
    email = "forchard@protonmail.com",
    url   = url("https://twitter.com/stormlogo")
     )
    )

  ThisBuild / description := "A set of utilities for developing portable machine learning Apache Spark applications developed bu Epiconcept."
  ThisBuild / licenses := List("MIT" -> new URL("https://mit-license.org/"))
  ThisBuild / homepage := Some(url("https://github.com/Epiconcept-Paris/sparkly"))

   // Remove all additional repository other than Maven Central from POM
  ThisBuild / pomIncludeRepository := { _ => false }
  ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
   }
  ThisBuild / publishMavenStyle := true
