import sbt._

object AppDependencies {
  private val mongoVer = "1.8.0"
  private val bootstrapVer = "8.5.0"
  private val pekkoVersion = "1.0.2"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"        %% "agent-mtd-identifiers"     % "2.0.0",
    "uk.gov.hmrc"        %% "bootstrap-backend-play-30" % bootstrapVer,
    "uk.gov.hmrc.mongo"  %% "hmrc-mongo-play-30"        % mongoVer,
    "com.github.blemale" %% "scaffeine"                 % "5.3.0",
    "org.typelevel"      %% "cats-core"                 % "2.12.0"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"     % bootstrapVer % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"         % "7.0.1"      % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30"    % mongoVer     % Test,
    "org.scalamock"          %% "scalamock"                  % "6.0.0"      % Test,
    "org.apache.pekko"       %% "pekko-testkit"              % pekkoVersion % Test,
    "org.apache.pekko"       %% "pekko-actor-testkit-typed"  % pekkoVersion % Test,
  )
}
