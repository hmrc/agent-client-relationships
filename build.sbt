import CodeCoverageSettings.scoverageSettings
import uk.gov.hmrc.DefaultBuildSettings

val appName = "agent-client-relationships"

ThisBuild / majorVersion := 1
ThisBuild / scalaVersion := "3.3.7"

val scalaCompilerOptions = Seq(
  "-Werror",
  "-language:implicitConversions",
  "-feature",
  "-Wconf:src=target/.*:s",
  "-Wconf:src=routes/.*:s"
)

lazy val root = (project in file("."))
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(
    name := appName,
    organization := "uk.gov.hmrc",
    PlayKeys.playDefaultPort := 9434,
    scalacOptions ++= scalaCompilerOptions,
    resolvers ++= Seq(
      Resolver.typesafeRepo("releases"),
    ),
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scoverageSettings,
    Compile / unmanagedResourceDirectories += baseDirectory.value / "resources",
    routesImport ++= Seq(
      "uk.gov.hmrc.agentclientrelationships.binders.PathBinders.given",
      "uk.gov.hmrc.agentclientrelationships.model.identifiers.Arn",
      "uk.gov.hmrc.domain.Nino",
      "uk.gov.hmrc.agentclientrelationships.model.InvitationStatus",
      "uk.gov.hmrc.agentclientrelationships.model.identifiers.NinoWithoutSuffix"
    ),

    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true,
    Compile / scalacOptions := (Compile / scalacOptions).value.distinct,
    Test / scalacOptions := (Test / scalacOptions).value.distinct,
    Compile / doc / scalacOptions := Seq(), //this will allow to have warnings in `doc` task
    Test / doc / scalacOptions := Seq(), //this will allow to have warnings in `doc` task
    Test / logBuffered := false
  )
  .enablePlugins(PlayScala, SbtDistributablesPlugin)

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(root % "compile->compile;test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.test)
  .settings(
    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true,
    Compile / scalacOptions ++= scalaCompilerOptions,
    Test / scalacOptions ++= scalaCompilerOptions,
    Compile / scalacOptions := (Compile / scalacOptions).value.distinct,
    Test / scalacOptions := (Test / scalacOptions).value.distinct,
    Test / logBuffered := false
  )
