plugins {
  id("software.sava.build.modules.gcp-kms")
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  // LogSilencer pins expected-failure loggers through the JDK logging backend.
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
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
  }
}
