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
      // test sources share the recompiled root
      "software.sava.kms.core.signing.*Tests"
    )
    targetTests = "software.sava.kms.core.signing.*Test*"
  }
}
