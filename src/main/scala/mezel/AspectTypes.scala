package mezel

import cats.implicits._
import io.circe._

object AspectTypes {
  final case class ScalacOptions(
      scalacopts: List[String],
      plugins: List[String],
      semanticdbPlugin: String,
      classpath: List[String],
      targetroot: String,
      outputClassJar: String,
      compilerVersion: ScalaVersion,
  ) derives Decoder

  final case class Sources(
      sources: List[String]
  ) derives Decoder

  final case class DependencySources(
      sourcejars: List[String]
  ) derives Decoder

  final case class BuildTarget(
      javaHome: String,
      scalaCompilerClasspath: List[String],
      compilerVersion: ScalaVersion,
      deps: List[String],
      directory: String,
      workspaceRoot: Option[String]
  ) derives Decoder

  final case class ScalaVersion(major: String, minor: String, patch: String) {
    def toVersion: String = s"${major}.${minor}.${patch}"
  }
  object ScalaVersion {
    given Decoder[ScalaVersion] = Decoder[String].emap { s =>
      val parts = s.split("\\.").toList
      parts match {
        case major :: minor :: patch :: Nil => Right(ScalaVersion(major, minor, patch))
        case _                              => Left(s"invalid scala version: ${s}")
      }
    }
  }
}
