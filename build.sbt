name := "play-optimizing-static-assets"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  filters,
  "org.webjars" %% "webjars-play" % "2.2.1-2",
  "org.webjars" % "bootstrap" % "3.1.1"
)

play.Project.playScalaSettings