package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class TxMonitorConfigTests {

  // JSON Tests

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "minSleepBetweenSigStatusPolling": "PT1S",
          "webSocketConfirmationTimeout": "PT10S",
          "retrySendDelay": "PT8S",
          "minBlocksRemainingToResend": 4
        }
        """;
    final var config = TxMonitorConfig.parseConfig(JsonIterator.parse(json));
    assertEquals(Duration.ofSeconds(1), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(10), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(8), config.retrySendDelay());
    assertEquals(4, config.minBlocksRemainingToResend());
  }

  @Test
  void testParseJsonDefaults() {
    final var json = "{}";
    final var config = TxMonitorConfig.parseConfig(JsonIterator.parse(json));
    assertEquals(Duration.ofSeconds(3), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(5), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(5), config.retrySendDelay());
    assertEquals(8, config.minBlocksRemainingToResend());
  }

  @Test
  void testParseJsonPartial() {
    final var json = """
        {
          "retrySendDelay": "PT15S"
        }
        """;
    final var config = TxMonitorConfig.parseConfig(JsonIterator.parse(json));
    assertEquals(Duration.ofSeconds(3), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(5), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(15), config.retrySendDelay());
    assertEquals(8, config.minBlocksRemainingToResend());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var config = TxMonitorConfig.parseConfig(JsonIterator.parse(json));
    assertNull(config);
  }

  @Test
  void testCreateDefault() {
    final var config = TxMonitorConfig.createDefault();
    assertEquals(Duration.ofSeconds(3), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(5), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(5), config.retrySendDelay());
    assertEquals(8, config.minBlocksRemainingToResend());
  }

  // Properties Tests

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("minSleepBetweenSigStatusPolling", "PT1S");
    properties.setProperty("webSocketConfirmationTimeout", "PT10S");
    properties.setProperty("retrySendDelay", "PT8S");
    properties.setProperty("minBlocksRemainingToResend", "4");

    final var config = TxMonitorConfig.parseConfig(properties);
    assertEquals(Duration.ofSeconds(1), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(10), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(8), config.retrySendDelay());
    assertEquals(4, config.minBlocksRemainingToResend());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var config = TxMonitorConfig.parseConfig(new Properties());
    assertEquals(Duration.ofSeconds(3), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(5), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(5), config.retrySendDelay());
    assertEquals(8, config.minBlocksRemainingToResend());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("tx.minSleepBetweenSigStatusPolling", "PT2S");
    properties.setProperty("tx.webSocketConfirmationTimeout", "PT15S");
    properties.setProperty("tx.retrySendDelay", "PT12S");
    properties.setProperty("tx.minBlocksRemainingToResend", "6");

    final var config = TxMonitorConfig.parseConfig("tx", properties);
    assertEquals(Duration.ofSeconds(2), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(15), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(12), config.retrySendDelay());
    assertEquals(6, config.minBlocksRemainingToResend());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("monitor.tx.minSleepBetweenSigStatusPolling", "PT0.5S");
    properties.setProperty("monitor.tx.webSocketConfirmationTimeout", "PT30S");
    properties.setProperty("monitor.tx.retrySendDelay", "PT20S");
    properties.setProperty("monitor.tx.minBlocksRemainingToResend", "12");

    final var config = TxMonitorConfig.parseConfig("monitor.tx.", properties);
    assertEquals(Duration.ofMillis(500), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(30), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(20), config.retrySendDelay());
    assertEquals(12, config.minBlocksRemainingToResend());
  }

  @Test
  void testParsePropertiesPartial() {
    final var properties = new Properties();
    properties.setProperty("retrySendDelay", "PT15S");

    final var config = TxMonitorConfig.parseConfig(properties);
    assertEquals(Duration.ofSeconds(3), config.minSleepBetweenSigStatusPolling());
    assertEquals(Duration.ofSeconds(5), config.webSocketConfirmationTimeout());
    assertEquals(Duration.ofSeconds(15), config.retrySendDelay());
    assertEquals(8, config.minBlocksRemainingToResend());
  }
}
