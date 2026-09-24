plugins {
  id("software.sava.build.modules.gcp-kms")
  id("software.sava.build.feature.hardening")
}

// Bridge until the pinned sava-build's gcp-kms patches map CEL, which
// google-cloud-kms lists from 2.100.0 on as three non-modular jars that split
// dev.cel.common (unused: grpc-xds bundles its own copy); keep it identical to
// that plugin's spec and delete it when adopting that release.
extraJavaModuleInfo {
  automaticModule("dev.cel:common", "dev.cel") {
    mergeJar("dev.cel:runtime")
    mergeJar("dev.cel:protobuf")
  }
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  // LogSilencer pins expected-failure loggers through the JDK logging backend.
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
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
      "software.sava.kms.google.LogSilencer*",
      // a git-ignored personal integration main that needs live GCP
      // credentials; it lives in the test sources, so it is not production code
      // and needs no ownership decline, and on a checkout without it this
      // exclusion simply matches nothing
      "software.sava.kms.google.Integ"
    )
    targetTests = "software.sava.kms.google.*Test*"
  }
}
