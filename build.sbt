import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences._

name := "r2d2"

version := "1.0"

scalaVersion := "2.11.4"

//External repositories
resolvers ++= Seq("Typesafe Repository"             at "http://repo.typesafe.com/typesafe/releases/")

libraryDependencies ++= {
  val akkaVersion = "2.3.7"
  val sprayVersion = "1.3.2"
  Seq(
    "com.typesafe.akka" %% "akka-actor"   % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j"   % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
    "joda-time"         %  "joda-time"    % "2.0",
    "org.joda"          % "joda-convert"  % "1.2",
    // Test dependencies
    "org.specs2"        %% "specs2"       % "2.3.13"      % "test"
  )
}

//Sourcecode formatting
scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(PreserveDanglingCloseParenthesis, true)
  .setPreference(RewriteArrowSymbols, true)
  .setPreference(AlignParameters, true)
  .setPreference(DoubleIndentClassDeclaration, true)
  .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 90)
