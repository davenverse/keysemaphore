import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val catsV = "2.6.1"
val catsEffectV = "3.1.1"
val specs2V = "4.12.3"
val kindProjectorV = "0.13.0"

ThisBuild / crossScalaVersions := Seq("2.12.14", "2.13.6", "3.0.1")

lazy val `keysemaphore` = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .aggregate(core.jvm, core.js, site)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "keysemaphore",
    mimaVersionCheckExcludedVersions := {
      if (isDotty.value) Set("0.2.0")
      else Set()
    },
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"           % catsV,
      "org.typelevel" %%% "cats-effect"         % catsEffectV,
      "org.typelevel" %%% "munit-cats-effect-3" % munitCatsEffectV % Test
    ),
  )

lazy val site = project.in(file("site"))
  .dependsOn(core.jvm)
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(DavenverseMicrositePlugin)
  .settings{
    micrositeDescription := "Keyed Semaphores"
  }
