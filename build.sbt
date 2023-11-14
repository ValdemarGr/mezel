enablePlugins(GraalVMNativeImagePlugin)

ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "io.github.valdemargr"

ThisBuild / tlBaseVersion := "0.0"
ThisBuild / tlUntaggedAreSnapshots := false
ThisBuild / tlSonatypeUseLegacyHost := true

ThisBuild / licenses := List(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
)
ThisBuild / developers := List(
  Developer(
    "valdemargr",
    "Valdemar Grange",
    "randomvald0069@gmail.com",
    url("https://github.com/valdemargr")
  )
)
ThisBuild / headerLicense := Some(
  HeaderLicense.Custom("Copyright (c) 2023 Valdemar Grange")
)
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
    "org.typelevel" %% "cats-parse" % "0.3.8",
    "io.circe" %% "circe-core" % "0.14.6",
    "io.circe" %% "circe-parser" % "0.14.6",
    "io.circe" %% "circe-generic" % "0.14.6",
    "co.fs2" %% "fs2-core" % "3.9.3",
    "co.fs2" %% "fs2-io" % "3.9.3",
    "org.tpolecat" %% "sourcepos" % "1.1.0",
    "io.github.valdemargr" %% "catch-effect" % "0.0.1",
    "org.typelevel" %% "kittens" % "3.1.0",
    "org.scalameta" %% "munit" % "1.0.0-M10" % Test,
    "org.typelevel" %% "munit-cats-effect" % "2.0.0-M3" % Test
  )
)

lazy val root = project
  .in(file("."))
  .settings(sharedSettings)
  .settings(
    name := "mezel",
    fork := true,
    graalVMNativeImageOptions ++= Seq(
      "--initialize-at-build-time",
      "--no-fallback"
    ),
    Compile / PB.targets := Seq(
      scalapb.gen(scala3Sources = true) -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
    )
  )

lazy val dev = project
  .in(file("dev"))
  .dependsOn(root)
  .settings(sharedSettings)
  .settings(
    name := "mezel",
    fork := true,
    graalVMNativeImageOptions ++= Seq(
      "--initialize-at-build-time",
      "--no-fallback"
    )
  ).enablePlugins(GraalVMNativeImagePlugin)
