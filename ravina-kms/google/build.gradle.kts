plugins {
  id("software.sava.build.modules.gcp-kms")
  id("software.sava.build.feature.hardening")
}

dependencies.constraints {
  implementation("io.grpc:grpc-netty-shaded:1.71.0!!")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  // LogSilencer pins expected-failure loggers through the JDK logging backend.
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("googleKms") {
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
      // integration main; requires live GCP credentials
      "software.sava.kms.google.Integ"
    )
    targetTests = "software.sava.kms.google.*Test*"
  }
}
