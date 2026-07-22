plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  // LogSilencer pins expected-failure loggers through the JDK logging backend;
  // a declared dependency beats the reflection this used to need.
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("backoff") {
    targetClasses = listOf(
      "software.sava.services.core.remote.call.Backoff",
      "software.sava.services.core.remote.call.RootBackoff",
      "software.sava.services.core.remote.call.FibonacciBackoffErrorHandler",
      "software.sava.services.core.remote.call.ExponentialBackoffErrorHandler",
      "software.sava.services.core.remote.call.LinearBackoffErrorHandler",
      "software.sava.services.core.remote.call.SingleBackoffErrorHandler"
    )
    targetTests = "software.sava.services.core.remote.call.*Test*"
  }
  mutation.register("capacity") {
    targetClasses = listOf(
      "software.sava.services.core.request_capacity.CapacityStateVal",
      "software.sava.services.core.request_capacity.CapacityConfig",
      "software.sava.services.core.request_capacity.CapacityConfig\$*"
    )
    targetTests = "software.sava.services.core.remote.call.*Test*,software.sava.services.core.request_capacity.*Test*"
  }
  mutation.register("loadBalance") {
    // NAKED_RECEIVER trialled 2026-07-22: fires here (numbers in
    // config/pitest/README.md); fluent receiver-typed calls are otherwise
    // invisible to STRONGER.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    targetClasses = listOf("software.sava.services.core.remote.load_balance.*")
    excludedClasses = listOf("software.sava.services.core.remote.load_balance.*Tests*")
    targetTests = "software.sava.services.core.remote.load_balance.*Test*"
  }
  mutation.register("calls") {
    targetClasses = listOf(
      "software.sava.services.core.remote.call.ComposedCall",
      "software.sava.services.core.remote.call.GreedyCall",
      "software.sava.services.core.remote.call.CourteousCall",
      "software.sava.services.core.remote.call.CourteousBalancedCall",
      "software.sava.services.core.remote.call.GreedyBalancedCall",
      "software.sava.services.core.remote.call.UncheckedBalancedCall"
    )
    targetTests = "software.sava.services.core.remote.call.*Test*"
  }
  mutation.register("config") {
    // NAKED_RECEIVER trialled 2026-07-22: fires here (numbers in
    // config/pitest/README.md); fluent receiver-typed calls are otherwise
    // invisible to STRONGER.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    targetClasses = listOf(
      "software.sava.services.core.config.*",
      "software.sava.services.core.net.http.WebHookConfig",
      "software.sava.services.core.net.http.WebHookConfig\$*",
      "software.sava.services.core.remote.call.BackoffConfig",
      "software.sava.services.core.remote.call.BackoffConfig\$*",
      "software.sava.services.core.remote.load_balance.LoadBalancerConfig",
      "software.sava.services.core.remote.load_balance.LoadBalancerConfig\$*"
    )
    excludedClasses = listOf(
      "software.sava.services.core.config.*Tests*",
      "software.sava.services.core.config.*Fuzz*"
    )
    targetTests = "software.sava.services.core.config.*Test*,software.sava.services.core.net.http.*Test*,software.sava.services.core.remote.call.*Test*,software.sava.services.core.remote.load_balance.*Test*"
  }
  mutation.register("errorTracking") {
    targetClasses = listOf("software.sava.services.core.request_capacity.trackers.RootErrorTracker")
    targetTests = "software.sava.services.core.request_capacity.trackers.*Test*,software.sava.services.core.remote.call.*Test*"
  }

  /// Catch-all: everything not claimed by a focused suite above, so a new
  /// class is mutated by default instead of silently exempted (the older
  /// allowlist targeting left 29 of 64 classes unmutated, including
  /// HttpErrorTracker and UriCapacityConfig). Exclusions name what another
  /// suite already owns; if one goes stale the class is merely mutated twice,
  /// which is slow rather than blind — the safe direction to fail.
  mutation.register("catchAll") {
    // NAKED_RECEIVER trialled 2026-07-22: fires here (numbers in
    // config/pitest/README.md); fluent receiver-typed calls are otherwise
    // invisible to STRONGER.
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
    targetClasses = listOf("software.sava.services.core.*")
    excludedClasses = listOf(
      // test and fuzz sources share the recompiled root; trailing wildcards
      // also cover their nested types
      "software.sava.services.core.*Tests*",
      "software.sava.services.core.*Fuzz*",
      // test-only logging scope; named for what it does rather than *Tests*,
      // so it needs an exclusion of its own (trailing * covers nested types)
      "software.sava.services.core.LogSilencer*",
      // owned by 'backoff'
      "software.sava.services.core.remote.call.Backoff",
      "software.sava.services.core.remote.call.RootBackoff",
      "software.sava.services.core.remote.call.*BackoffErrorHandler",
      // owned by 'calls'
      "software.sava.services.core.remote.call.ComposedCall",
      "software.sava.services.core.remote.call.GreedyCall",
      "software.sava.services.core.remote.call.CourteousCall",
      "software.sava.services.core.remote.call.*BalancedCall",
      // owned by 'capacity'
      "software.sava.services.core.request_capacity.CapacityStateVal",
      "software.sava.services.core.request_capacity.CapacityConfig",
      "software.sava.services.core.request_capacity.CapacityConfig\$*",
      // owned by 'loadBalance'
      "software.sava.services.core.remote.load_balance.*",
      // owned by 'config'
      "software.sava.services.core.config.*",
      "software.sava.services.core.net.http.WebHookConfig",
      "software.sava.services.core.net.http.WebHookConfig\$*",
      "software.sava.services.core.remote.call.BackoffConfig",
      "software.sava.services.core.remote.call.BackoffConfig\$*",
      // owned by 'errorTracking'
      "software.sava.services.core.request_capacity.trackers.RootErrorTracker"
    )
    targetTests = "software.sava.services.core.*Test*"
  }

  fuzz.register("backoff") {
    targetClass = "software.sava.services.core.remote.call.BackoffFuzz"
    maxLen = 64
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/backoff")
  }
  fuzz.register("capacityConfig") {
    targetClass = "software.sava.services.core.request_capacity.CapacityConfigFuzz"
    maxLen = 512
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/capacityConfig")
  }
  fuzz.register("capacityState") {
    targetClass = "software.sava.services.core.request_capacity.CapacityStateFuzz"
    maxLen = 512
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/capacityState")
  }
  fuzz.register("configs") {
    targetClass = "software.sava.services.core.config.ConfigsFuzz"
    maxLen = 768
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/configs")
  }
  fuzz.register("configParity") {
    // differential: the same logical config rendered as JSON and as
    // Properties must parse to equal values, or both paths must reject it
    targetClass = "software.sava.services.core.config.ConfigParityFuzz"
    // the harness derives field values from a fixed set of leading bytes;
    // longer inputs add nothing
    maxLen = 32
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/configParity")
  }
}
