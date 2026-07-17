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
    targetClasses = listOf("software.sava.services.solana.alt.ScoredTable")
    targetTests = "software.sava.services.solana.alt.*Test*"
  }
  mutation.register("formatting") {
    targetClasses = listOf(
      "software.sava.services.solana.config.ChainItemFormatter",
      "software.sava.services.solana.config.ChainItemFormatter\$*"
    )
    targetTests = "software.sava.services.solana.config.*Test*"
  }
}
