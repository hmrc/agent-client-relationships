import sbt.*

object AppDependencies {

  private val bootstrapVer = "10.3.0"
  private val mongoVer = "2.10.0"
  private val playVer = "play-30"
  private val pekkoVer = "1.0.3"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% s"bootstrap-backend-$playVer" % bootstrapVer,
    "uk.gov.hmrc.mongo"      %% s"hmrc-mongo-$playVer"        % mongoVer,
    "uk.gov.hmrc"            %% s"domain-$playVer"            % "11.0.0",
    "uk.gov.hmrc"            %% s"crypto-json-$playVer"       % "8.4.0",
    "org.typelevel"          %% "cats-core"                   % "2.13.0",
    "io.github.samueleresca" %% "pekko-quartz-scheduler"      % "1.2.0-pekko-1.0.x"
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"       %% s"bootstrap-test-$playVer"  % bootstrapVer,
    "uk.gov.hmrc.mongo" %% s"hmrc-mongo-test-$playVer" % mongoVer,
    "org.apache.pekko"  %% "pekko-testkit"             % pekkoVer,
    "org.apache.pekko"  %% "pekko-actor-testkit-typed" % pekkoVer,
    "org.scalacheck"    %% "scalacheck"                % "1.19.0"
  ).map(_ % Test)
}
