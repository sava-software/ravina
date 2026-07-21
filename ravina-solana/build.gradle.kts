plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  // LogSilencer pins expected-failure loggers through the JDK logging backend;
  // a declared dependency beats reflection here.
  requires("java.logging")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("epoch") {
    targetClasses = listOf(
      "software.sava.services.solana.epoch.Epoch",
      "software.sava.services.solana.epoch.SlotPerformanceStats"
    )
    targetTests = "software.sava.services.solana.epoch.*Test*"
  }
  mutation.register("alt") {
    targetClasses = listOf(
      "software.sava.services.solana.alt.ScoredTable",
      "software.sava.services.solana.alt.ScoredTableMeta"
    )
    targetTests = "software.sava.services.solana.alt.*Test*"
  }
  mutation.register("formatting") {
    targetClasses = listOf(
      "software.sava.services.solana.config.ChainItemFormatter",
      "software.sava.services.solana.config.ChainItemFormatter\$*"
    )
    targetTests = "software.sava.services.solana.config.*Test*"
  }
  mutation.register("fees") {
    targetClasses = listOf("software.sava.services.solana.transactions.SimulationFutures")
    targetTests = "software.sava.services.solana.transactions.*Test*"
  }
  mutation.register("config") {
    targetClasses = listOf(
      "software.sava.services.solana.config.HeliusConfig",
      "software.sava.services.solana.config.HeliusConfig\$*",
      "software.sava.services.solana.epoch.EpochServiceConfig",
      "software.sava.services.solana.epoch.EpochServiceConfig\$*",
      "software.sava.services.solana.transactions.TxMonitorConfig",
      "software.sava.services.solana.transactions.TxMonitorConfig\$*",
      "software.sava.services.solana.alt.TableCacheConfig",
      "software.sava.services.solana.alt.TableCacheConfig\$*",
      "software.sava.services.solana.remote.call.CallWeights",
      "software.sava.services.solana.remote.call.CallWeights\$*"
    )
    targetTests = "software.sava.services.solana.config.*Test*,software.sava.services.solana.epoch.*Test*,software.sava.services.solana.transactions.*Test*,software.sava.services.solana.alt.*Test*,software.sava.services.solana.remote.call.*Test*"
  }

  /// Catch-all: everything not claimed by a focused suite above, so a new
  /// class is mutated by default instead of silently exempted (the older
  /// allowlist targeting left 31 of 42 classes unmutated, including all of
  /// transactions/ and the vendored helius client). Exclusions name what
  /// another suite already owns; a stale one costs a duplicate run, not a
  /// blind spot.
  mutation.register("catchAll") {
    targetClasses = listOf("software.sava.services.solana.*")
    excludedClasses = listOf(
      "software.sava.services.solana.*Tests*",
      "software.sava.services.solana.*Fuzz*",
      // test-only logging scope; named for what it does rather than *Tests*,
      // so it needs an exclusion of its own (trailing * covers nested types)
      "software.sava.services.solana.LogSilencer*",
      // owned by 'epoch'
      "software.sava.services.solana.epoch.Epoch",
      "software.sava.services.solana.epoch.SlotPerformanceStats",
      // owned by 'alt'
      "software.sava.services.solana.alt.ScoredTable",
      "software.sava.services.solana.alt.ScoredTableMeta",
      // owned by 'formatting'
      "software.sava.services.solana.config.ChainItemFormatter",
      "software.sava.services.solana.config.ChainItemFormatter\$*",
      // owned by 'fees'
      "software.sava.services.solana.transactions.SimulationFutures",
      // owned by 'config'
      "software.sava.services.solana.config.HeliusConfig",
      "software.sava.services.solana.config.HeliusConfig\$*",
      "software.sava.services.solana.epoch.EpochServiceConfig",
      "software.sava.services.solana.epoch.EpochServiceConfig\$*",
      "software.sava.services.solana.transactions.TxMonitorConfig",
      "software.sava.services.solana.transactions.TxMonitorConfig\$*",
      "software.sava.services.solana.alt.TableCacheConfig",
      "software.sava.services.solana.alt.TableCacheConfig\$*",
      "software.sava.services.solana.remote.call.CallWeights",
      "software.sava.services.solana.remote.call.CallWeights\$*"
    )
    targetTests = "software.sava.services.solana.*Test*"
  }

  fuzz.register("configs") {
    targetClass = "software.sava.services.solana.config.SolanaConfigsFuzz"
    maxLen = 768
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/configs")
  }
  fuzz.register("configParity") {
    // differential: the same logical config rendered as JSON and as
    // Properties must parse to equal values, or both paths must reject it
    targetClass = "software.sava.services.solana.config.SolanaConfigParityFuzz"
    // the harness derives field values from a fixed set of leading bytes;
    // longer inputs add nothing
    maxLen = 32
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/configParity")
  }
}
