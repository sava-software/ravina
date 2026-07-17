package software.sava.services.core.request_capacity;

import systems.comodal.jsoniter.JsonIterator;

/// Jazzer entry point for CapacityConfig JSON parsing, driven by the `fuzzCapacityConfig` Gradle task.
/// Deliberately free of Jazzer imports so it compiles with the ordinary test sources.
public final class CapacityConfigFuzz {

  public static void fuzzerTestOneInput(final byte[] data) {
    final CapacityConfig config;
    try {
      config = CapacityConfig.parse(JsonIterator.parse(data));
    } catch (final RuntimeException tolerated) {
      return;
    }
    if (config == null) {
      return;
    }
    if (config.maxGroupedErrorResponses() == 0) {
      throw new AssertionError("maxGroupedErrorResponses must default to a non-zero value");
    }
    if (config.serverErrorBackOffDuration() == null) {
      throw new AssertionError("serverErrorBackOffDuration must never be null");
    }
    final CapacityConfig again;
    try {
      again = CapacityConfig.parse(JsonIterator.parse(data));
    } catch (final RuntimeException e) {
      throw new AssertionError("parse accepted then rejected identical input", e);
    }
    if (!config.equals(again)) {
      throw new AssertionError("CapacityConfig.parse is not deterministic");
    }
  }

  private CapacityConfigFuzz() {
  }
}
