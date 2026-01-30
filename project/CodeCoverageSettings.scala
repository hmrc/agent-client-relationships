import sbt.Def

object CodeCoverageSettings {

  private val excludedPackages: Seq[String] = Seq(
    "uk.gov.hmrc.BuildInfo",
    ".*Routes.*",
    ".*RoutesPrefix.*",
    ".*Filters?",
    "MicroserviceAuditConnector",
    "Module",
    "GraphiteStartUp",
    "ErrorHandler",
    ".*.Reverse[^.]*",
    "uk.gov.hmrc.agentclientrelationships.testOnly.controllers.*",
    "uk.gov.hmrc.agentclientrelationships.testOnly.models.*",
    "testOnly.*",
    "testOnlyDoNotUseInAppConf.*"
  )

  lazy val scoverageSettings: Seq[Def.Setting[_ >: String with Double with Boolean]] = {
    import scoverage.ScoverageKeys
    Seq(
      ScoverageKeys.coverageExcludedPackages := excludedPackages.mkString(";"),
      ScoverageKeys.coverageMinimumStmtTotal := 85.00,
      //ScoverageKeys.coverageMinimumStmtPerFile := 80.00,
      ScoverageKeys.coverageFailOnMinimum := false,
      ScoverageKeys.coverageHighlighting := true
    )
  }

}
