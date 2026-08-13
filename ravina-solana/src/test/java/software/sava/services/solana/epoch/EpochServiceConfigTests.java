package software.sava.services.solana.epoch;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class EpochServiceConfigTests {

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "defaultMillisPerSlot": 420,
          "minMillisPerSlot": 380,
          "maxMillisPerSlot": 600,
          "slotSampleWindow": "PT30M",
          "fetchSlotSamplesDelay": "PT10M",
          "fetchEpochInfoAfterEndDelay": "PT5S"
        }
        """;
    final var config = EpochServiceConfig.parseConfig(JsonIterator.parse(json));

    assertEquals(420, config.defaultMillisPerSlot());
    assertEquals(380, config.minMillisPerSlot());
    assertEquals(600, config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(30), config.slotSampleWindow());
    assertEquals(Duration.ofMinutes(10), config.fetchSlotSamplesDelay());
    assertEquals(Duration.ofSeconds(5), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParseJsonDefaults() {
    final var json = """
        {}
        """;
    final var config = EpochServiceConfig.parseConfig(JsonIterator.parse(json));
    final var defaults = EpochServiceConfig.createDefault();

    assertEquals(defaults.defaultMillisPerSlot(), config.defaultMillisPerSlot());
    assertEquals(defaults.minMillisPerSlot(), config.minMillisPerSlot());
    assertEquals(defaults.maxMillisPerSlot(), config.maxMillisPerSlot());
    assertEquals(defaults.slotSampleWindow(), config.slotSampleWindow());
    assertEquals(defaults.fetchSlotSamplesDelay(), config.fetchSlotSamplesDelay());
    assertEquals(defaults.fetchEpochInfoAfterEndDelay(), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParseJsonPartial() {
    final var json = """
        {
          "defaultMillisPerSlot": 450,
          "slotSampleWindow": "PT15M"
        }
        """;
    final var config = EpochServiceConfig.parseConfig(JsonIterator.parse(json));
    final var defaults = EpochServiceConfig.createDefault();

    assertEquals(450, config.defaultMillisPerSlot());
    assertEquals(defaults.minMillisPerSlot(), config.minMillisPerSlot());
    assertEquals(defaults.maxMillisPerSlot(), config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(15), config.slotSampleWindow());
    assertEquals(defaults.fetchSlotSamplesDelay(), config.fetchSlotSamplesDelay());
    assertEquals(defaults.fetchEpochInfoAfterEndDelay(), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var config = EpochServiceConfig.parseConfig(JsonIterator.parse(json));
    assertNull(config);
  }

  @Test
  void testCreateDefault() {
    final var config = EpochServiceConfig.createDefault();

    // With no samples, this stays near the fastest target so slower rollout
    // stages prompt earlier checks instead of sleeping past chain progress.
    assertEquals(210, config.defaultMillisPerSlot());
    assertEquals(190, config.minMillisPerSlot());
    assertEquals(500, config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(21), config.slotSampleWindow());
    assertEquals(Duration.ofMinutes(8), config.fetchSlotSamplesDelay());
    assertEquals(Duration.ofSeconds(1), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void slotDurationBoundsMustBeOrdered() {
    final var defaults = EpochServiceConfig.createDefault();
    final var ex = assertThrows(IllegalArgumentException.class, () -> new EpochServiceConfig(
        defaults.defaultMillisPerSlot(),
        501,
        500,
        defaults.slotSampleWindow(),
        defaults.fetchSlotSamplesDelay(),
        defaults.fetchEpochInfoAfterEndDelay()
    ));
    assertEquals(
        "Minimum millis per slot (501) cannot exceed maximum millis per slot (500).",
        ex.getMessage()
    );

    assertDoesNotThrow(() -> new EpochServiceConfig(
        defaults.defaultMillisPerSlot(),
        500,
        500,
        defaults.slotSampleWindow(),
        defaults.fetchSlotSamplesDelay(),
        defaults.fetchEpochInfoAfterEndDelay()
    ));
  }

  @Test
  void slotDurationsMustBePositiveWhenConstructedDirectly() {
    final var defaults = EpochServiceConfig.createDefault();

    assertAll(
        () -> {
          final var exception = assertThrows(IllegalArgumentException.class, () -> new EpochServiceConfig(
              0,
              defaults.minMillisPerSlot(),
              defaults.maxMillisPerSlot(),
              defaults.slotSampleWindow(),
              defaults.fetchSlotSamplesDelay(),
              defaults.fetchEpochInfoAfterEndDelay()
          ));
          assertTrue(exception.getMessage().contains("defaultMillisPerSlot"), exception.getMessage());
        },
        () -> {
          final var exception = assertThrows(IllegalArgumentException.class, () -> new EpochServiceConfig(
              defaults.defaultMillisPerSlot(),
              0,
              defaults.maxMillisPerSlot(),
              defaults.slotSampleWindow(),
              defaults.fetchSlotSamplesDelay(),
              defaults.fetchEpochInfoAfterEndDelay()
          ));
          assertTrue(exception.getMessage().contains("minMillisPerSlot"), exception.getMessage());
        },
        () -> {
          final var exception = assertThrows(IllegalArgumentException.class, () -> new EpochServiceConfig(
              defaults.defaultMillisPerSlot(),
              defaults.minMillisPerSlot(),
              0,
              defaults.slotSampleWindow(),
              defaults.fetchSlotSamplesDelay(),
              defaults.fetchEpochInfoAfterEndDelay()
          ));
          assertTrue(exception.getMessage().contains("maxMillisPerSlot"), exception.getMessage());
        }
    );
  }

  @Test
  void parsedSlotDurationsMustBePositive() {
    final var jsonException = assertThrows(
        IllegalArgumentException.class,
        () -> EpochServiceConfig.parseConfig(JsonIterator.parse("{\"defaultMillisPerSlot\":0}"))
    );
    assertTrue(jsonException.getMessage().contains("defaultMillisPerSlot"), jsonException.getMessage());

    final var properties = new Properties();
    properties.setProperty("minMillisPerSlot", "-1");
    final var propertiesException = assertThrows(
        IllegalArgumentException.class,
        () -> EpochServiceConfig.parseConfig(properties)
    );
    assertTrue(propertiesException.getMessage().contains("minMillisPerSlot"), propertiesException.getMessage());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("defaultMillisPerSlot", "420");
    properties.setProperty("minMillisPerSlot", "380");
    properties.setProperty("maxMillisPerSlot", "600");
    properties.setProperty("slotSampleWindow", "PT30M");
    properties.setProperty("fetchSlotSamplesDelay", "PT10M");
    properties.setProperty("fetchEpochInfoAfterEndDelay", "PT5S");

    final var config = EpochServiceConfig.parseConfig(properties);

    assertEquals(420, config.defaultMillisPerSlot());
    assertEquals(380, config.minMillisPerSlot());
    assertEquals(600, config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(30), config.slotSampleWindow());
    assertEquals(Duration.ofMinutes(10), config.fetchSlotSamplesDelay());
    assertEquals(Duration.ofSeconds(5), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var config = EpochServiceConfig.parseConfig(new Properties());
    final var defaults = EpochServiceConfig.createDefault();

    assertEquals(defaults.defaultMillisPerSlot(), config.defaultMillisPerSlot());
    assertEquals(defaults.minMillisPerSlot(), config.minMillisPerSlot());
    assertEquals(defaults.maxMillisPerSlot(), config.maxMillisPerSlot());
    assertEquals(defaults.slotSampleWindow(), config.slotSampleWindow());
    assertEquals(defaults.fetchSlotSamplesDelay(), config.fetchSlotSamplesDelay());
    assertEquals(defaults.fetchEpochInfoAfterEndDelay(), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("epoch.defaultMillisPerSlot", "420");
    properties.setProperty("epoch.minMillisPerSlot", "380");
    properties.setProperty("epoch.maxMillisPerSlot", "600");
    properties.setProperty("epoch.slotSampleWindow", "PT30M");
    properties.setProperty("epoch.fetchSlotSamplesDelay", "PT10M");
    properties.setProperty("epoch.fetchEpochInfoAfterEndDelay", "PT5S");

    final var config = EpochServiceConfig.parseConfig("epoch", properties);

    assertEquals(420, config.defaultMillisPerSlot());
    assertEquals(380, config.minMillisPerSlot());
    assertEquals(600, config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(30), config.slotSampleWindow());
    assertEquals(Duration.ofMinutes(10), config.fetchSlotSamplesDelay());
    assertEquals(Duration.ofSeconds(5), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("service.epoch.defaultMillisPerSlot", "420");
    properties.setProperty("service.epoch.minMillisPerSlot", "380");
    properties.setProperty("service.epoch.maxMillisPerSlot", "600");
    properties.setProperty("service.epoch.slotSampleWindow", "PT30M");
    properties.setProperty("service.epoch.fetchSlotSamplesDelay", "PT10M");
    properties.setProperty("service.epoch.fetchEpochInfoAfterEndDelay", "PT5S");

    final var config = EpochServiceConfig.parseConfig("service.epoch", properties);

    assertEquals(420, config.defaultMillisPerSlot());
    assertEquals(380, config.minMillisPerSlot());
    assertEquals(600, config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(30), config.slotSampleWindow());
    assertEquals(Duration.ofMinutes(10), config.fetchSlotSamplesDelay());
    assertEquals(Duration.ofSeconds(5), config.fetchEpochInfoAfterEndDelay());
  }

  @Test
  void testParsePropertiesPartial() {
    final var properties = new Properties();
    properties.setProperty("defaultMillisPerSlot", "450");
    properties.setProperty("slotSampleWindow", "PT15M");

    final var config = EpochServiceConfig.parseConfig(properties);
    final var defaults = EpochServiceConfig.createDefault();

    assertEquals(450, config.defaultMillisPerSlot());
    assertEquals(defaults.minMillisPerSlot(), config.minMillisPerSlot());
    assertEquals(defaults.maxMillisPerSlot(), config.maxMillisPerSlot());
    assertEquals(Duration.ofMinutes(15), config.slotSampleWindow());
    assertEquals(defaults.fetchSlotSamplesDelay(), config.fetchSlotSamplesDelay());
    assertEquals(defaults.fetchEpochInfoAfterEndDelay(), config.fetchEpochInfoAfterEndDelay());
  }
}
