import sbt._

object AppDependencies {
  private val mongoVer = "0.74.0"
  private val bootstrapVer = "7.15.0"

  val compile = Seq(
    "uk.gov.hmrc"        %% "agent-mtd-identifiers"     % "1.5.0",
    "uk.gov.hmrc"        %% "bootstrap-backend-play-28" % bootstrapVer,
    "uk.gov.hmrc"        %% "agent-kenshoo-monitoring"  % "5.3.0" exclude("uk.gov.hmrc", "bootstrap-backend-play-28"),
    "uk.gov.hmrc.mongo"  %% "hmrc-mongo-play-28"        % mongoVer,
    "com.kenshoo"        %% "metrics-play"              % "2.7.3_0.8.2",
    "com.github.blemale" %% "scaffeine"                 % "4.0.1",
    "org.typelevel"      %% "cats-core"                 % "2.6.1"
  )

  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"   % bootstrapVer % "test, it",
    "org.scalatestplus.play" %% "scalatestplus-play"       % "5.1.0"      % "test, it",
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28"  % mongoVer     % "test, it",
    "org.scalatestplus"      %% "mockito-3-12"             % "3.2.10.0"   % "test, it",
    "com.github.tomakehurst" %  "wiremock-jre8"            % "2.26.1"     % "test, it",
    "org.scalamock"          %% "scalamock"                % "4.4.0"      % "test, it",
    "com.vladsch.flexmark"   %  "flexmark-all"             % "0.35.10"    % "test, it",
    "com.typesafe.akka"      %% "akka-actor-testkit-typed" % "2.6.20"     % "test, it"
  )
}
