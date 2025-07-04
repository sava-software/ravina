pluginManagement {
  repositories {
    gradlePluginPortal()
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      credentials(PasswordCredentials::class)
    }
  }
}

plugins {
  id("software.sava.build") version "0.1.24"
  // https://github.com/gradlex-org/extra-java-module-info/releases
  id("org.gradlex.extra-java-module-info") version "1.12" apply false
}

rootProject.name = "ravina"

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")

    module("ravina-kms/core") { artifact = "ravina-kms-core" }
    module("ravina-kms/http") { artifact = "ravina-kms-http" }
    module("ravina-kms/google") { artifact = "ravina-kms-google" }
  }
}
