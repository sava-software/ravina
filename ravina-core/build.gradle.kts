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

  fuzz.register("backoff") {
    targetClass = "software.sava.services.core.remote.call.BackoffFuzz"
    maxLen = 64
  }
  fuzz.register("capacityConfig") {
    targetClass = "software.sava.services.core.request_capacity.CapacityConfigFuzz"
    maxLen = 512
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/capacityConfig")
  }
  fuzz.register("capacityState") {
    targetClass = "software.sava.services.core.request_capacity.CapacityStateFuzz"
    maxLen = 512
  }
}
