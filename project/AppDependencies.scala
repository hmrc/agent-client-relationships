import sbt.*

object AppDependencies {
  private val mongoVer = "2.6.0"
  private val bootstrapVer = "9.13.0"
  private val pekkoVersion = "1.0.3"
  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "agent-mtd-identifiers"     % "2.2.0",
    "uk.gov.hmrc"            %% "bootstrap-backend-play-30" % bootstrapVer,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-30"        % mongoVer,
    "org.typelevel"          %% "cats-core"                 % "2.13.0",
    "uk.gov.hmrc"            %% "crypto-json-play-30"       % "8.2.0",
    "io.github.samueleresca" %% "pekko-quartz-scheduler"    % "1.2.0-pekko-1.0.x"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"    % bootstrapVer % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"        % "7.0.1"      % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30"   % mongoVer     % Test,
    "org.scalamock"          %% "scalamock"                 % "7.3.2"      % Test,
    "org.apache.pekko"       %% "pekko-testkit"             % pekkoVersion % Test,
    "org.apache.pekko"       %% "pekko-actor-testkit-typed" % pekkoVersion % Test
  )
}
