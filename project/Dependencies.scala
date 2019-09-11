
import sbt._
import Versions._
object Dependencies {
  val actor = "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion
  val commonDependencies: Seq[ModuleID] = Seq(actor)
}
