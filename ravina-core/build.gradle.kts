plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
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
    targetClasses = listOf("software.sava.services.core.remote.load_balance.*")
    excludedClasses = listOf("software.sava.services.core.remote.load_balance.*Tests")
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
      "software.sava.services.core.config.*Tests",
      "software.sava.services.core.config.*Fuzz"
    )
    targetTests = "software.sava.services.core.config.*Test*,software.sava.services.core.net.http.*Test*,software.sava.services.core.remote.call.*Test*,software.sava.services.core.remote.load_balance.*Test*"
  }
  mutation.register("errorTracking") {
    targetClasses = listOf("software.sava.services.core.request_capacity.trackers.RootErrorTracker")
    targetTests = "software.sava.services.core.request_capacity.trackers.*Test*,software.sava.services.core.remote.call.*Test*"
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
