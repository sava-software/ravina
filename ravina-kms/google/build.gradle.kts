plugins {
  id("software.sava.build.modules.gcp-kms")
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  // KmsClientClassPathTests and KmsLivePathTests build the Google client themselves: gax for
  // NoCredentialsProvider, the auth library for a service-account credential. Both are in the
  // gcp-kms convention's client family, so these edges carry its exclusions too.
  requires("com.google.api.gax")
  requires("com.google.auth.oauth2")
  // LogSilencer pins expected-failure loggers through the JDK logging backend.
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

// KmsLivePathTests reaches cloudkms.googleapis.com and the OAuth token endpoint, so it is tagged
// 'live' and runs only through liveTest below: never under test, check, CI or PIT (the googleKms
// suite excludes the class from its selection). AGENTS.md's "tests never hit the network" names
// this as its one opt-in exception.
tasks.test {
  useJUnitPlatform {
    excludeTags("live")
  }
}

tasks.register<Test>("liveTest") {
  group = "verification"
  description = "Runs the 'live'-tagged KMS tests against Google's public endpoints (network)."
  // The tagged class comes from the test source set but runs on the class path: it exercises the
  // Google client's transport and auth paths, where module boundaries add nothing, and the gradlex
  // whitebox wiring reaches only the suite's own test task.
  val test = sourceSets.test.get()
  testClassesDirs = test.output.classesDirs
  classpath = test.runtimeClasspath
  modularity.inferModulePath = false
  useJUnitPlatform {
    includeTags("live")
  }
  // The outcome is the remote side's answer, which Gradle cannot track.
  doNotTrackState("Reaches Google's public endpoints, whose answers Gradle cannot track.")
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

hardening {
  // The git-ignored Integ.java main lives in the test sources on a dev machine and
  // nowhere in CI; kept out of the PIT/Jazzer recompile, it cannot make one checkout's
  // mutant population or tool class path differ from another's.
  recompileExcludes = listOf("Integ.java")
  mutation.register("googleKms") {
    // NAKED_RECEIVER trialled 2026-07-22: fires here (numbers in
    // config/pitest/README.md); fluent receiver-typed calls are otherwise
    // invisible to STRONGER.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    // catch-all by exclusion, so a new class is mutated by default instead of
    // silently skipped
    targetClasses = listOf("software.sava.kms.google.*")
    excludedClasses = listOf(
      // test sources share the recompiled root; the trailing wildcard also
      // covers nested/anonymous classes inside test classes
      "software.sava.kms.google.*Tests*",
      // test-only logging scope; named for what it does rather than *Tests*,
      // so it needs an exclusion of its own (trailing * covers nested types)
      "software.sava.kms.google.LogSilencer*"
    )
    targetTests = "software.sava.kms.google.*Test*"
    excludeTestClass(
      "software.sava.kms.google.KmsLivePathTests",
      "reaches Google's public endpoints over the network; run only by liveTest, never by test or PIT"
    )
  }
}
