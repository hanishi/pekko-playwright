ThisBuild / organization := "org.apache.pekko"

name := "crawler"

scalaVersion := "3.3.4"
val pekkoVersion = "1.1.5"
val pekkoHttpVersion = "1.2.0"
val logbackVersion = "1.5.18"
val pekkoQuartzSchedulerVersion = "1.3.0-pekko-1.1.x"

scalacOptions :=
  Seq("-feature", "-unchecked", "-deprecation", "-encoding", "utf8")

run / fork := true
Compile / run / fork := true

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
  "io.github.samueleresca" %% "pekko-quartz-scheduler" %
    pekkoQuartzSchedulerVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
  "com.microsoft.playwright" % "playwright" % "1.53.0",
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
  "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
  "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,
)
