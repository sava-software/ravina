rootProject.name = "ravina"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
      .orNull?.takeIf { it.isNotBlank() }
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
      .orNull?.takeIf { it.isNotBlank() }
    if (gprUser != null && gprToken != null) {
      maven {
        name = "savaGithubPackages"
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
  }
//  if (settingsDir.resolve("../sava-build").isDirectory) {
//    includeBuild("../sava-build")
//  }
}

plugins {
  id("software.sava.build") version "21.5.6"
  id("software.sava.build.feature.jdk-provisioning") version "21.5.6"
}

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")

    module("ravina-kms/core") { artifact = "ravina-kms-core" }
    module("ravina-kms/http") { artifact = "ravina-kms-http" }
    module("ravina-kms/google") { artifact = "ravina-kms-google" }
  }
}
