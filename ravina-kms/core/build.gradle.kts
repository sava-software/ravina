plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("signing") {
    // catch-all by exclusion, so a new class is mutated by default instead of
    // silently skipped
    targetClasses = listOf("software.sava.kms.core.signing.*")
    excludedClasses = listOf(
      // test sources share the recompiled root; the trailing wildcard also
      // covers nested/anonymous classes inside test classes
      "software.sava.kms.core.signing.*Tests*",
      // fuzz harnesses share the recompiled root
      "software.sava.kms.core.signing.*Fuzz"
    )
    targetTests = "software.sava.kms.core.signing.*Test*"
  }

  fuzz.register("signingConfig") {
    // field-order invariance over the mark()/reset() deferred re-parse
    targetClass = "software.sava.kms.core.signing.SigningServiceConfigFuzz"
    // the harness derives a field mask and a backoff delay from two bytes and
    // builds the documents itself; longer inputs add nothing
    maxLen = 16
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/signingConfig")
  }
}
