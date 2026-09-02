// This repository builds two ways, and this file has to serve both.
//
//   standalone            a seconds-long test loop for the core itself
//   generators/riscvhw    symlinked into Chipyard, which applies this file as
//                         settings for its own `riscvhw` project
//
// So it must contain settings only, never a `lazy val` project definition:
// inside Chipyard that would declare a second project in Chipyard's build and
// the generator would not be found. Sodor's build.sbt has the same shape for
// the same reason.

name         := "riscvhw"
organization := "eecsmap"
version      := "0.1.0"
scalaVersion := "2.13.16"

// Chipyard supplies Chisel through its own rocketLibDeps, at the same version
// pinned here, so declaring it twice is harmless -- but chiseltest is only
// needed for the standalone tests.
libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel"     % "6.7.0",
  "edu.berkeley.cs"   %% "chiseltest" % "6.0.0" % Test,
  "org.scalatest"     %% "scalatest"  % "3.2.19" % Test,
)

addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.7.0" cross CrossVersion.full)

scalacOptions ++= Seq(
  "-deprecation", "-feature", "-unchecked",
  "-language:reflectiveCalls",
  // Catches a field read before its initializer runs, which in Chisel shows up
  // as a silently-zero wire rather than an error. It has already caught one
  // real bug: CSR trap ports wired before the exception logic that feeds them.
  "-Xcheckinit",
)
