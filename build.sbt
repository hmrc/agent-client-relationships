import CodeCoverageSettings.scoverageSettings
import uk.gov.hmrc.DefaultBuildSettings

val appName = "agent-client-relationships"

ThisBuild / majorVersion := 1
ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    name := appName,
    organization := "uk.gov.hmrc",
    PlayKeys.playDefaultPort := 9434,
    scalacOptions ++= Seq(
      "-Yrangepos",
      "-Xlint:-missing-interpolator,_",
//      "-Yno-adapted-args",
      "-Ywarn-value-discard",
      "-Ywarn-dead-code",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-Wconf:src=target/.*:s", // silence warnings from compiled files
      "-Wconf:src=Routes/.*:s"  // silence warnings from routes files
    ),
    resolvers ++= Seq(
      Resolver.typesafeRepo("releases"),
    ),
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scoverageSettings,
    Compile / unmanagedResourceDirectories += baseDirectory.value / "resources",
    routesImport ++= Seq("uk.gov.hmrc.agentclientrelationships.binders.PathBinders._"),
    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true
  )
  .enablePlugins(PlayScala, SbtDistributablesPlugin)

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(root % "compile->compile;test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.test)
  .settings(
    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true
  )


