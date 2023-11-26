addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.7")
addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.5.4")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.16")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.1.5")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.14"
