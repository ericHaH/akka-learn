version in ThisBuild := "1.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.13.0"

scalacOptions in ThisBuild ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-encoding",
  "UTF-8",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-language:higherKinds"
)

organization := "com.eric"