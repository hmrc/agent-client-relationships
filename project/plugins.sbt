resolvers += "HMRC-open-artefacts-maven" at "https://open.artefacts.tax.service.gov.uk/maven2"
resolvers += Resolver.url("HMRC-open-artefacts-ivy", url("https://open.artefacts.tax.service.gov.uk/ivy2"))(Resolver.ivyStylePatterns)

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("org.playframework"  % "sbt-plugin"          % "3.0.7")
addSbtPlugin("uk.gov.hmrc"        % "sbt-auto-build"      % "3.24.0")
addSbtPlugin("uk.gov.hmrc"        % "sbt-distributables"  % "2.6.0")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"        % "2.5.4")
addSbtPlugin("org.scoverage"      % "sbt-scoverage"       % "2.3.1")
addSbtPlugin("org.scalastyle"     %% "scalastyle-sbt-plugin" % "1.0.0")
addSbtPlugin("org.scalariform"    %% "sbt-scalariform"       % "1.8.3")
addSbtPlugin("org.wartremover"    %  "sbt-wartremover"       % "3.3.3")
addSbtPlugin("com.timushev.sbt"   %  "sbt-updates"           % "0.6.4")
addDependencyTreePlugin
