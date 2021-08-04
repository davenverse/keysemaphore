import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val catsV = "2.6.1"
val catsEffectV = "2.5.2"
val specs2V = "4.12.3"

ThisBuild / crossScalaVersions := Seq("2.12.14", "2.13.6", "3.0.1")

lazy val `keysemaphore` = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .aggregate(core.jvm, core.js, docs)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure).in(file("core"))
  .disablePlugins(MimaPlugin)
  .settings(
    name := "keysemaphore",
    libraryDependencies ++= Seq(
      "org.typelevel"               %%% "cats-core"                  % catsV,
      "org.typelevel"               %%% "cats-effect"                % catsEffectV,

      ("org.specs2"                  %%% "specs2-core"                % specs2V       % Test).cross(CrossVersion.for3Use2_13),
      ("org.specs2"                  %%% "specs2-scalacheck"          % specs2V       % Test).cross(CrossVersion.for3Use2_13)
    )
  )

lazy val docs = project.in(file("docs"))
  .dependsOn(core.jvm)
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(DavenverseMicrositePlugin)
  .settings{
    micrositeDescription := "Keyed Semaphores"
  }


