import play.core.PlayVersion
import uk.gov.hmrc.SbtAutoBuildPlugin
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin._

lazy val scoverageSettings = {
  import scoverage.ScoverageKeys
  Seq(
    // Semicolon-separated list of regexs matching classes to exclude
    ScoverageKeys.coverageExcludedPackages := """uk\.gov\.hmrc\.BuildInfo;.*\.Routes;.*\.RoutesPrefix;.*Filters?;MicroserviceAuditConnector;Module;GraphiteStartUp;.*\.Reverse[^.]*""",
    ScoverageKeys.coverageMinimumStmtTotal := 80.00,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true,
    Test / parallelExecution := false
  )
}

lazy val compileDeps = Seq(
  "uk.gov.hmrc" %% "bootstrap-backend-play-28" % "6.3.0",
  "uk.gov.hmrc" %% "agent-mtd-identifiers" % "0.43.0-play-28",
  "uk.gov.hmrc" %% "agent-kenshoo-monitoring" % "4.8.0-play-28",
  "uk.gov.hmrc" %% "simple-reactivemongo" % "8.1.0-play-28",
  "uk.gov.hmrc" %% "mongo-lock" % "7.1.0-play-28",
  "com.kenshoo" %% "metrics-play" % "2.7.3_0.8.2",
  "com.github.blemale" %% "scaffeine" % "4.0.1",
  "org.typelevel" %% "cats-core" % "2.6.1",
  "com.typesafe.play" %% "play-json-joda" % "2.9.2"
)

def testDeps(scope: String) = Seq(
  "uk.gov.hmrc" %% "reactivemongo-test" % "5.1.0-play-28" % scope,
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.1.0" % scope,
  "org.scalatestplus" %% "mockito-3-12" % "3.2.10.0" % scope,
  "com.github.tomakehurst" % "wiremock-jre8" % "2.26.1" % scope,
  "org.scalamock" %% "scalamock" % "4.4.0" % scope,
  "com.vladsch.flexmark" % "flexmark-all" % "0.35.10" % scope
)

def tmpMacWorkaround(): Seq[ModuleID] =
  if (sys.props.get("os.name").fold(false)(_.toLowerCase.contains("mac")))
    Seq("org.reactivemongo" % "reactivemongo-shaded-native" % "0.18.6-osx-x86-64" % "runtime,test,it")
  else Seq()

lazy val root = (project in file("."))
  .settings(
    name := "agent-client-relationships",
    organization := "uk.gov.hmrc",
    PlayKeys.playDefaultPort := 9434,
    majorVersion := 0,
    scalaVersion := "2.12.15",
    scalacOptions ++= Seq(
      "-Yrangepos",
//      "-Xfatal-warnings",
      "-Xlint:-missing-interpolator,_",
      "-Yno-adapted-args",
      "-Ywarn-value-discard",
      "-Ywarn-dead-code",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-P:silencer:pathFilters=views;routes"),
    resolvers ++= Seq(
      Resolver.typesafeRepo("releases"),
    ),
    resolvers += "HMRC-local-artefacts-maven" at "https://artefacts.tax.service.gov.uk/artifactory/hmrc-releases-local",
    libraryDependencies ++= tmpMacWorkaround() ++ compileDeps ++ testDeps("test") ++ testDeps("it"),
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" % "silencer-plugin" % "1.7.8" cross CrossVersion.full),
      "com.github.ghik" % "silencer-lib" % "1.7.8" % Provided cross CrossVersion.full
    ),
    publishingSettings,
    scoverageSettings,
    Compile / unmanagedResourceDirectories += baseDirectory.value / "resources",
    routesImport ++= Seq("uk.gov.hmrc.agentclientrelationships.binders.PathBinders._"),
    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true
  )
  .configs(IntegrationTest)
  .settings(
    IntegrationTest / Keys.fork := false,
    Defaults.itSettings,
    IntegrationTest / unmanagedSourceDirectories += baseDirectory(_ / "it").value,
    IntegrationTest / parallelExecution := false
  )
  .enablePlugins(PlayScala, SbtDistributablesPlugin)

inConfig(IntegrationTest)(scalafmtCoreSettings)

