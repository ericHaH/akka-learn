
import Dependencies._
import ProjectBuild._
lazy val akkaLearn = project.init(file("."),Some("akka-learn"))
lazy val akkaActor = project.init(file("akka-actor"),Some("akka-actor"),commonDependencies)
