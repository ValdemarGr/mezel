val scala213Version = "2.13.12"

ThisBuild / scalaVersion := scala213Version
ThisBuild / crossScalaVersions := Seq(scala213Version, "3.3.0")
ThisBuild / organization := "io.github.valdemargr"

ThisBuild / tlBaseVersion := "0.0"
ThisBuild / tlUntaggedAreSnapshots := false
ThisBuild / tlSonatypeUseLegacyHost := true

ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer("valdemargr", "Valdemar Grange", "randomvald0069@gmail.com", url("https://github.com/valdemargr"))
)
ThisBuild / headerLicense := Some(HeaderLicense.Custom("Copyright (c) 2023 Valdemar Grange"))
ThisBuild / headerEmptyLine := false

lazy val sharedSettings = Seq(
  organization := "io.github.valdemargr",
  organizationName := "Valdemar Grange",
  autoCompilerPlugins := true,
  tlCiMimaBinaryIssueCheck := false,
  tlMimaPreviousVersions := Set.empty,
  mimaReportSignatureProblems := false,
  mimaFailOnProblem := false,
  mimaPreviousArtifacts := Set.empty,
  scalacOptions ++= {
    if (scalaVersion.value.startsWith("2")) {
      Seq(
        "-Wunused:-nowarn",
        "-Wconf:cat=unused-nowarn:s",
        "-Ywarn-unused:-nowarn"
      )
    } else Seq.empty // Seq("-explain")
  },
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "3.5.2",
    "org.typelevel" %% "cats-core" % "2.9.0",
    "co.fs2" %% "fs2-core" % "3.9.3",
    "co.fs2" %% "fs2-io" % "3.9.3",
    "org.tpolecat" %% "sourcepos" % "1.1.0",
    "org.scalameta" %% "munit" % "1.0.0-M10" % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" % Test
  )
)

lazy val root = project
  .in(file("."))
  .settings(sharedSettings)
  .settings(name := "mezel")
