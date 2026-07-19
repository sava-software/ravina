plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
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

  fuzz.register("configs") {
    targetClass = "software.sava.services.solana.config.SolanaConfigsFuzz"
    maxLen = 768
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/configs")
  }
}
