import sbt._
import Keys._

object ProjectBuild {
  implicit class init(val project: Project) {
    def init(
        file: File,
        projectName: Option[String] = None,
        dependencies: Seq[ModuleID] = Seq.empty,
        projectSettings: Seq[Def.Setting[
          _ >: String with Task[Seq[String]] <: java.io.Serializable]] =
          Seq.empty,
        depends: Seq[Project] = Seq.empty
    ): Project = {
      val defineName = projectName match {
        case None    => file.getName
        case Some(n) => n
      }
      val defineSettings = projectSettings :+ (libraryDependencies ++= dependencies)
      project
        .in(file)
        .settings(
          name := defineName,
          defineSettings
        )
    }
  }
}
