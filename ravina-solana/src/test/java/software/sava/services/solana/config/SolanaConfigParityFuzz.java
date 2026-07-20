package software.sava.services.solana.config;

import software.sava.services.solana.alt.TableCacheConfig;
import software.sava.services.solana.epoch.EpochServiceConfig;
import software.sava.services.solana.remote.call.CallWeights;
import software.sava.services.solana.transactions.TxMonitorConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.function.Function;

/// Jazzer entry point for the ravina-solana JSON-versus-Properties
/// differential, driven by the `fuzzConfigParity` Gradle task. Deliberately
/// free of Jazzer imports so it compiles with the ordinary test sources.
///
/// Counterpart to ravina-core's `ConfigParityFuzz`. Each of these configs is
/// parseable two ways, and the two paths carry **independent copies of the
/// field list** — JSON names in a `FieldMatcher` ordinal switch or a
/// `fieldEquals` chain, the property keys in a separate `parseProperties`.
/// Nothing but review keeps them in step, and the existing `SolanaConfigsFuzz`
/// harness cannot see drift: it only checks that a document does not blow up.
///
/// The `FieldMatcher` parsers make this sharper than it looks — those switch
/// on an ordinal whose index is positionally coupled to the matcher's
/// declaration order, so a field inserted in the middle silently shifts every
/// later case. That shows up here as a value parsed into the wrong component.
public final class SolanaConfigParityFuzz {

  private interface Parity {

    void check(final byte[] data);
  }

  private static final Parity[] PARITIES = {
      SolanaConfigParityFuzz::epochServiceParity,
      SolanaConfigParityFuzz::txMonitorParity,
      SolanaConfigParityFuzz::tableCacheParity,
      SolanaConfigParityFuzz::callWeightsParity,
      SolanaConfigParityFuzz::chainItemFormatterParity
  };

  /// `ServiceConfigUtil.parseDuration` accepts `PT13S` or a bare `13S`;
  /// alternating on a seed byte keeps both spellings exercised.
  private static String duration(final Duration duration, final boolean bare) {
    final var iso = duration.toString();
    return bare && iso.startsWith("PT") ? iso.substring(2) : iso;
  }

  private static Duration seconds(final int value) {
    return Duration.ofSeconds(1 + Math.abs(value % 3_600));
  }

  private static int unsigned(final byte[] data, final int index) {
    return data[index % data.length] & 0xFF;
  }

  /// Both paths must agree: the same value when both succeed, and both must
  /// reject the same input. A one-sided rejection is itself a divergence.
  private static <T> void assertParity(final String name,
                                       final String json,
                                       final Properties properties,
                                       final Function<String, T> parseJson,
                                       final Function<Properties, T> parseProperties) {
    T fromJson = null;
    RuntimeException jsonFailure = null;
    try {
      fromJson = parseJson.apply(json);
    } catch (final RuntimeException e) {
      jsonFailure = e;
    }

    T fromProperties = null;
    RuntimeException propertiesFailure = null;
    try {
      fromProperties = parseProperties.apply(properties);
    } catch (final RuntimeException e) {
      propertiesFailure = e;
    }

    if ((jsonFailure == null) != (propertiesFailure == null)) {
      throw new AssertionError(String.format(
          "%s: one path rejected what the other accepted.%n  json: %s%n  properties: %s%n  document: %s",
          name,
          jsonFailure == null ? "accepted " + fromJson : "rejected " + jsonFailure,
          propertiesFailure == null ? "accepted " + fromProperties : "rejected " + propertiesFailure,
          json
      ));
    }
    if (jsonFailure == null && !fromJson.equals(fromProperties)) {
      throw new AssertionError(String.format(
          "%s: the two paths parsed the same input differently.%n  json: %s%n  properties: %s%n  document: %s",
          name, fromJson, fromProperties, json
      ));
    }
  }

  private static void epochServiceParity(final byte[] data) {
    final int defaultMillisPerSlot = 1 + unsigned(data, 1);
    final int minMillisPerSlot = 1 + unsigned(data, 2);
    final int maxMillisPerSlot = 1 + unsigned(data, 3);
    final var slotSampleWindow = seconds(unsigned(data, 4));
    final var fetchSlotSamplesDelay = seconds(unsigned(data, 5));
    final var fetchEpochInfoAfterEndDelay = seconds(unsigned(data, 6));
    final boolean bare = (unsigned(data, 7) & 1) == 1;

    final var json = String.format("""
            {"defaultMillisPerSlot":%d,"minMillisPerSlot":%d,"maxMillisPerSlot":%d,\
            "slotSampleWindow":"%s","fetchSlotSamplesDelay":"%s","fetchEpochInfoAfterEndDelay":"%s"}""",
        defaultMillisPerSlot, minMillisPerSlot, maxMillisPerSlot,
        duration(slotSampleWindow, bare), duration(fetchSlotSamplesDelay, bare),
        duration(fetchEpochInfoAfterEndDelay, bare)
    );

    final var properties = new Properties();
    properties.setProperty("defaultMillisPerSlot", Integer.toString(defaultMillisPerSlot));
    properties.setProperty("minMillisPerSlot", Integer.toString(minMillisPerSlot));
    properties.setProperty("maxMillisPerSlot", Integer.toString(maxMillisPerSlot));
    properties.setProperty("slotSampleWindow", duration(slotSampleWindow, bare));
    properties.setProperty("fetchSlotSamplesDelay", duration(fetchSlotSamplesDelay, bare));
    properties.setProperty("fetchEpochInfoAfterEndDelay", duration(fetchEpochInfoAfterEndDelay, bare));

    assertParity(
        "EpochServiceConfig",
        json,
        properties,
        j -> EpochServiceConfig.parseConfig(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> EpochServiceConfig.parseConfig("", p)
    );
  }

  private static void txMonitorParity(final byte[] data) {
    final var minSleep = seconds(unsigned(data, 1));
    final var webSocketConfirmationTimeout = seconds(unsigned(data, 2));
    final var retrySendDelay = seconds(unsigned(data, 3));
    final int minBlocksRemainingToResend = unsigned(data, 4);
    final boolean bare = (unsigned(data, 5) & 1) == 1;

    final var json = String.format("""
            {"minSleepBetweenSigStatusPolling":"%s","webSocketConfirmationTimeout":"%s",\
            "retrySendDelay":"%s","minBlocksRemainingToResend":%d}""",
        duration(minSleep, bare), duration(webSocketConfirmationTimeout, bare),
        duration(retrySendDelay, bare), minBlocksRemainingToResend
    );

    final var properties = new Properties();
    properties.setProperty("minSleepBetweenSigStatusPolling", duration(minSleep, bare));
    properties.setProperty("webSocketConfirmationTimeout", duration(webSocketConfirmationTimeout, bare));
    properties.setProperty("retrySendDelay", duration(retrySendDelay, bare));
    properties.setProperty("minBlocksRemainingToResend", Integer.toString(minBlocksRemainingToResend));

    assertParity(
        "TxMonitorConfig",
        json,
        properties,
        j -> TxMonitorConfig.parseConfig(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> TxMonitorConfig.parseConfig("", p)
    );
  }

  private static void tableCacheParity(final byte[] data) {
    final int initialCapacity = 1 + unsigned(data, 1);
    final var refreshStaleItemsDelay = seconds(unsigned(data, 2));
    final var consideredStale = seconds(unsigned(data, 3));
    final boolean bare = (unsigned(data, 4) & 1) == 1;

    final var json = String.format("""
            {"initialCapacity":%d,"refreshStaleItemsDelay":"%s","consideredStale":"%s"}""",
        initialCapacity, duration(refreshStaleItemsDelay, bare), duration(consideredStale, bare)
    );

    final var properties = new Properties();
    properties.setProperty("initialCapacity", Integer.toString(initialCapacity));
    properties.setProperty("refreshStaleItemsDelay", duration(refreshStaleItemsDelay, bare));
    properties.setProperty("consideredStale", duration(consideredStale, bare));

    assertParity(
        "TableCacheConfig",
        json,
        properties,
        j -> TableCacheConfig.parse(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> TableCacheConfig.parseConfig("", p)
    );
  }

  private static void callWeightsParity(final byte[] data) {
    final int getProgramAccounts = unsigned(data, 1);
    final int getTransaction = unsigned(data, 2);
    final int sendTransaction = unsigned(data, 3);

    final var json = String.format("""
            {"getProgramAccounts":%d,"getTransaction":%d,"sendTransaction":%d}""",
        getProgramAccounts, getTransaction, sendTransaction
    );

    final var properties = new Properties();
    properties.setProperty("getProgramAccounts", Integer.toString(getProgramAccounts));
    properties.setProperty("getTransaction", Integer.toString(getTransaction));
    properties.setProperty("sendTransaction", Integer.toString(sendTransaction));

    assertParity(
        "CallWeights",
        json,
        properties,
        j -> CallWeights.parse(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> CallWeights.parseConfig("", p)
    );
  }

  private static void chainItemFormatterParity(final byte[] data) {
    // Format strings carry a single %s; vary the surrounding text only, so the
    // values stay legal for both encodings.
    final var sig = String.format("https://explorer-%d.test/tx/%%s", unsigned(data, 1));
    final var address = String.format("https://explorer-%d.test/account/%%s", unsigned(data, 2));

    final var json = String.format("""
        {"sig":"%s","address":"%s"}""", sig, address);

    final var properties = new Properties();
    properties.setProperty("sig", sig);
    properties.setProperty("address", address);

    assertParity(
        "ChainItemFormatter",
        json,
        properties,
        j -> ChainItemFormatter.parseFormatter(JsonIterator.parse(j.getBytes(StandardCharsets.UTF_8))),
        p -> ChainItemFormatter.parseConfig("", p)
    );
  }

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 8) {
      return;
    }
    PARITIES[(data[0] & 0xFF) % PARITIES.length].check(data);
  }

  private SolanaConfigParityFuzz() {
  }
}
