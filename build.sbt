// Versions pinned to Chipyard 1.14.0 so this core can be dropped into
// generators/ later without a version conflict.
val chiselVersion    = "6.7.0"
val chiselTestVersion = "6.0.0"

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "eecsmap"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "riscvhw",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"     % chiselVersion,
      "edu.berkeley.cs"   %% "chiseltest" % chiselTestVersion % Test,
      "org.scalatest"     %% "scalatest"  % "3.2.19"          % Test,
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
    scalacOptions ++= Seq(
      "-deprecation", "-feature", "-unchecked",
      "-language:reflectiveCalls",
      "-Xcheckinit",
    ),
  )
