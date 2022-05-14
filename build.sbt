import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val catsV = "2.6.1"
val catsEffectV = "3.2.1"
val munitCatsEffectV = "1.0.5"
val kindProjectorV = "0.13.2"

ThisBuild / crossScalaVersions := Seq("2.12.14", "2.13.8", "3.0.1")

lazy val `keysemaphore` = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .aggregate(core.jvm, core.js, site)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "keysemaphore",
    mimaVersionCheckExcludedVersions := Set("0.2.0", "0.2.1"),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"           % catsV,
      "org.typelevel" %%% "cats-effect"         % catsEffectV,
      "org.typelevel" %%% "munit-cats-effect-3" % munitCatsEffectV % Test
    )
  )
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )

lazy val site = project
  .in(file("site"))
  .dependsOn(core.jvm)
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .enablePlugins(DavenverseMicrositePlugin)
  .settings {
    micrositeDescription := "Keyed Semaphores"
  }
