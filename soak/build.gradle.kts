import java.util.Properties

plugins {
  java
  application
}

// ---------------------------------------------------------------------------------------
// Source layout: 'src-main/java', NOT 'src/main/java'.
//
// The root build's settings.gradle.kts registers every direct subdirectory of the repo root
// as a subproject through gradlex java-module-dependencies:
//
//   javaModules { directory(".") { group = "software.sava"; plugin("software.sava.build.java-module") } }
//
// A subdirectory is auto-included as a subproject if, and only if, at least one
// 'src/<sourceSet>/java/module-info.java' exists inside it (proven in sava on 2026-09-21:
// the root build then fails configuration outright). Moving the source root one name
// sideways is the least invasive fix that needs no edit to the root settings.gradle.kts:
// the listing only ever looks under '<dir>/src/'. Keep it that way: never create
// 'soak/src/'.
// ---------------------------------------------------------------------------------------
sourceSets {
  main {
    java.setSrcDirs(listOf("src-main/java"))
    resources.setSrcDirs(listOf("src-main/resources"))
  }
  test {
    java.setSrcDirs(listOf("src-test/java"))
    resources.setSrcDirs(listOf("src-test/resources"))
  }
}

val gprUser = providers.gradleProperty("savaGithubPackagesUsername").orNull
val gprToken = providers.gradleProperty("savaGithubPackagesPassword").orNull

repositories {
  mavenCentral()
  // Everything sava publishes beyond sava-core/sava-rpc/json-iterator, the version catalog
  // and the idl clients included, is on GitHub Packages only. One URL serves the whole org.
  if (!gprUser.isNullOrBlank() && !gprToken.isNullOrBlank()) {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
      credentials {
        username = gprUser
        password = gprToken
      }
    }
  }
}

// Soak JVM selection. Plain toolchain detection takes whichever JDK 25 it finds first, and on
// the development machine that is GraalVM CE 25 rather than the openjdk-25.0.2 the JFR
// behaviour in the plan was measured against, a silent swap of the JVM whose flight recorder,
// GC and jfr/jcmd tooling the run depends on. Pin the vendor so compilation and launch agree,
// and keep both escape hatches: '-PsoakJvmVendor=<match>' (or 'any' to take whatever is
// detected) and '-PsoakJavaHome=<JAVA_HOME>' to launch an entirely different JDK.
val soakJvmVendor: String = providers.gradleProperty("soakJvmVendor").getOrElse("Oracle")

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
    if (soakJvmVendor.isNotBlank() && soakJvmVendor != "any") {
      vendor = JvmVendorSpec.matching(soakJvmVendor)
    }
  }
  // Default since Gradle 7, stated because this harness is modular on purpose: the soak JVM
  // is launched with -p/-m so it sees the same module graph a consumer does.
  modularity.inferModulePath = true
}

// ravina's modules declare their external dependencies without versions; the parent build
// pins them through consistent resolution against the solana-version-catalog platform, and
// that constraint does not cross the composite-build boundary. Apply the same platform here,
// at the same version the parent pins in gradle/sava.properties.
val savaProperties = Properties()
rootDir.resolve("../gradle/sava.properties").reader().use(savaProperties::load)
val solanaBOMVersion: String = savaProperties.getProperty("solanaBOMVersion")

dependencies {
  implementation(platform("software.sava:solana-version-catalog:$solanaBOMVersion"))
  // The subject. Substituted by the composite to the local ':ravina-solana' project, and with
  // it ':ravina-core' and ':ravina-kms-core'; sava-core, sava-rpc and the spl idl client come
  // from the platform at the versions the parent pins.
  implementation("software.sava:ravina-solana")
}

application {
  mainModule = "software.sava.ravina.soak"
  mainClass = "software.sava.ravina.soak.Main"
}

// Writes the runtime module path and the toolchain launcher, so soak.sh can start the JVM
// itself (with its own -XX:StartFlightRecording line) instead of going through Gradle.
val soakModulePathFiles = files(tasks.named<Jar>("jar").map { it.archiveFile }, configurations.named("runtimeClasspath"))
val soakLauncher: Provider<String> = providers.gradleProperty("soakJavaHome")
  .map { "$it/bin/java" }
  .orElse(javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile.absolutePath })
val soakOutputDir = layout.buildDirectory.dir("soak")

// The guard for the source-layout rule above. A 'soak/src/' directory would be auto-included
// by the root build as a subproject the next time anyone ran it from the repository root, so
// its mere existence fails this build before the module path is written, with the reason.
tasks.register("assertNoSrcDir") {
  group = "soak"
  description = "Fails if soak/src exists: the root build would auto-include it as a subproject."
  val srcDir = layout.projectDirectory.dir("src").asFile
  doLast {
    if (srcDir.exists()) {
      throw GradleException("soak/src must not exist (found ${srcDir}): sources live in soak/src-main/java, "
          + "because the root build auto-includes any soak/src/*/java/module-info.java as a subproject "
          + "and then fails configuration. Move the files and delete the directory.")
    }
  }
}

tasks.register("soakModulePath") {
  group = "soak"
  description = "Writes build/soak/module-path.txt and build/soak/java.txt for a direct, non-Gradle launch."
  dependsOn("assertNoSrcDir")
  inputs.files(soakModulePathFiles)
  inputs.property("launcher", soakLauncher)
  outputs.dir(soakOutputDir)
  val separator = File.pathSeparator
  doLast {
    val dir = soakOutputDir.get().asFile
    dir.mkdirs()
    dir.resolve("module-path.txt")
      .writeText(soakModulePathFiles.joinToString(separator) { it.absolutePath } + "\n")
    dir.resolve("java.txt").writeText(soakLauncher.get() + "\n")
  }
}
