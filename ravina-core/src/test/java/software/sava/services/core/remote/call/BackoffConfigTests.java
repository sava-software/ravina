package software.sava.services.core.remote.call;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class BackoffConfigTests {

  @Test
  void testParseJsonDefaults() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {}"""));
    assertNotNull(config);
    assertEquals(BackoffStrategy.exponential, config.strategy());
    assertEquals(Duration.ofSeconds(1), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(32), config.maxRetryDelay());
  }

  @Test
  void testParseJsonAllFields() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {
          "strategy": "fibonacci",
          "initialRetryDelay": "PT2S",
          "maxRetryDelay": "PT60S"
        }"""));
    assertNotNull(config);
    assertEquals(BackoffStrategy.fibonacci, config.strategy());
    assertEquals(Duration.ofSeconds(2), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(60), config.maxRetryDelay());
  }

  @Test
  void testParseJsonSecondsFields() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {
          "strategy": "linear",
          "initialRetryDelaySeconds": 5,
          "maxRetryDelaySeconds": 120
        }"""));
    assertNotNull(config);
    assertEquals(BackoffStrategy.linear, config.strategy());
    assertEquals(Duration.ofSeconds(5), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(120), config.maxRetryDelay());
  }

  @Test
  void testParseJsonNull() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        null"""));
    assertNull(config);
  }

  @Test
  void testParseJsonUpperCaseStrategy() {
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {"strategy": "FIBONACCI"}"""));
    assertEquals(BackoffStrategy.fibonacci, config.strategy());
  }

  @Test
  void testParseJsonUnknownFieldIsSkippedEntirely() {
    // The value after the unknown field only parses if the skip consumed it.
    final var config = BackoffConfig.parseConfig(JsonIterator.parse("""
        {"junk": {"nested": [true]}, "maxRetryDelaySeconds": 9}"""));
    assertEquals(Duration.ofSeconds(9), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesUpperCaseStrategy() {
    final var properties = new Properties();
    properties.setProperty("strategy", "FIBONACCI");
    final var config = BackoffConfig.parse(properties);
    assertEquals(BackoffStrategy.fibonacci, config.strategy());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var properties = new Properties();
    final var config = BackoffConfig.parse(properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.exponential, config.strategy());
    assertEquals(Duration.ofSeconds(1), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(32), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("strategy", "fibonacci");
    properties.setProperty("initialRetryDelay", "PT2S");
    properties.setProperty("maxRetryDelay", "PT60S");

    final var config = BackoffConfig.parse(properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.fibonacci, config.strategy());
    assertEquals(Duration.ofSeconds(2), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(60), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("backoff.strategy", "single");
    properties.setProperty("backoff.initialRetryDelay", "PT0.5S");
    properties.setProperty("backoff.maxRetryDelay", "PT10S");

    final var config = BackoffConfig.parse("backoff", properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.single, config.strategy());
    assertEquals(Duration.ofMillis(500), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(10), config.maxRetryDelay());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("my.service.backoff.strategy", "linear");
    properties.setProperty("my.service.backoff.initialRetryDelay", "PT3S");
    properties.setProperty("my.service.backoff.maxRetryDelay", "PT45S");

    final var config = BackoffConfig.parse("my.service.backoff.", properties);
    assertNotNull(config);
    assertEquals(BackoffStrategy.linear, config.strategy());
    assertEquals(Duration.ofSeconds(3), config.initialRetryDelay());
    assertEquals(Duration.ofSeconds(45), config.maxRetryDelay());
  }

  private static Backoff createBackoff(final BackoffStrategy strategy,
                                       final Duration initial,
                                       final Duration max) {
    return new BackoffConfig(strategy, initial, max).createBackoff();
  }

  @Test
  void fibonacciRunsInSecondsForWholeSecondDelays() {
    final var backoff = createBackoff(BackoffStrategy.fibonacci, Duration.ofSeconds(1), Duration.ofSeconds(13));
    assertEquals(TimeUnit.SECONDS, backoff.timeUnit());
    // clean fibonacci steps, not the 1134ms a nanosecond sequence starts at
    assertEquals(1, backoff.delay(1, TimeUnit.SECONDS));
    assertEquals(2, backoff.delay(2, TimeUnit.SECONDS));
    assertEquals(3, backoff.delay(3, TimeUnit.SECONDS));
    assertEquals(5, backoff.delay(4, TimeUnit.SECONDS));
    assertEquals(1_000, backoff.delay(1, TimeUnit.MILLISECONDS));
  }

  @Test
  void fibonacciFallsBackToMillisForSubSecondDelays() {
    final var backoff = createBackoff(BackoffStrategy.fibonacci, Duration.ofMillis(100), Duration.ofSeconds(2));
    assertEquals(TimeUnit.MILLISECONDS, backoff.timeUnit());
    // 89 is the fibonacci number nearest 100
    assertEquals(89, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(144, backoff.delay(2, TimeUnit.MILLISECONDS));
    assertEquals(2_000, backoff.delay(Integer.MAX_VALUE, TimeUnit.MILLISECONDS));
  }

  @Test
  void aDelayThatIsNotWholeSecondsKeepsItsPrecision() {
    // 1.5s is not truncated to 1s: the whole config drops to milliseconds
    final var backoff = createBackoff(BackoffStrategy.fibonacci, Duration.ofMillis(1_500), Duration.ofSeconds(60));
    assertEquals(TimeUnit.MILLISECONDS, backoff.timeUnit());
    assertEquals(1_597, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(60_000, backoff.delay(Integer.MAX_VALUE, TimeUnit.MILLISECONDS));
  }

  @Test
  void theMaxDelayAlsoDrivesTheGranularity() {
    // the initial delay alone would allow seconds; the max delay does not
    final var backoff = createBackoff(BackoffStrategy.linear, Duration.ofSeconds(1), Duration.ofMillis(1_500));
    assertEquals(TimeUnit.MILLISECONDS, backoff.timeUnit());
    assertEquals(1_000, backoff.delay(1, TimeUnit.MILLISECONDS));
    assertEquals(1_500, backoff.delay(Integer.MAX_VALUE, TimeUnit.MILLISECONDS));
  }

  @Test
  void exponentialAndLinearAcceptMicrosecondGranularity() {
    for (final var strategy : new BackoffStrategy[]{BackoffStrategy.exponential, BackoffStrategy.linear}) {
      final var backoff = createBackoff(strategy, Duration.ofNanos(500_000), Duration.ofSeconds(1));
      assertEquals(TimeUnit.MICROSECONDS, backoff.timeUnit(), strategy.name());
      assertEquals(500, backoff.delay(1, TimeUnit.MICROSECONDS), strategy.name());

      final var wholeSeconds = createBackoff(strategy, Duration.ofSeconds(1), Duration.ofSeconds(32));
      assertEquals(TimeUnit.SECONDS, wholeSeconds.timeUnit(), strategy.name());
      assertEquals(1, wholeSeconds.delay(1, TimeUnit.SECONDS), strategy.name());
      // the absolute delays are unchanged by the coarser unit
      assertEquals(32_000, wholeSeconds.delay(Integer.MAX_VALUE, TimeUnit.MILLISECONDS), strategy.name());
    }
  }

  @Test
  void aDelayExactlyAtTheFloorIsAccepted() {
    // one microsecond is the finest exponential and linear accept, and one
    // millisecond the finest fibonacci accepts: the boundary itself is legal
    for (final var strategy : new BackoffStrategy[]{BackoffStrategy.exponential, BackoffStrategy.linear}) {
      final var backoff = createBackoff(strategy, Duration.ofNanos(1_000), Duration.ofSeconds(1));
      assertEquals(TimeUnit.MICROSECONDS, backoff.timeUnit(), strategy.name());
      assertEquals(1, backoff.delay(1, TimeUnit.MICROSECONDS), strategy.name());
    }
    final var fibonacci = createBackoff(BackoffStrategy.fibonacci, Duration.ofMillis(1), Duration.ofSeconds(1));
    assertEquals(TimeUnit.MILLISECONDS, fibonacci.timeUnit());
    assertEquals(1, fibonacci.delay(1, TimeUnit.MILLISECONDS));
  }

  @Test
  void subMicrosecondDelaysAreRejected() {
    for (final var strategy : new BackoffStrategy[]{BackoffStrategy.exponential, BackoffStrategy.linear}) {
      final var tooSmall = new BackoffConfig(strategy, Duration.ofNanos(999), Duration.ofSeconds(1));
      assertTrue(
          assertThrows(IllegalArgumentException.class, tooSmall::createBackoff).getMessage()
              .contains("at least one microsecond"),
          strategy.name()
      );
      // large enough, but not expressible in whole microseconds
      final var tooFine = new BackoffConfig(strategy, Duration.ofNanos(1_500), Duration.ofSeconds(1));
      assertTrue(
          assertThrows(IllegalArgumentException.class, tooFine::createBackoff).getMessage()
              .contains("whole microseconds"),
          strategy.name()
      );
      // and a max delay finer than a microsecond is rejected for the same reason
      final var fineMax = new BackoffConfig(strategy, Duration.ofSeconds(1), Duration.ofNanos(1_500_000_500L));
      assertThrows(IllegalArgumentException.class, fineMax::createBackoff, strategy.name());
    }
  }

  @Test
  void fibonacciRejectsSubMillisecondDelays() {
    for (final var tooSmall : new Duration[]{Duration.ofNanos(999_999), Duration.ZERO, Duration.ofMillis(-1)}) {
      final var config = new BackoffConfig(BackoffStrategy.fibonacci, tooSmall, Duration.ofSeconds(13));
      assertTrue(
          assertThrows(IllegalArgumentException.class, config::createBackoff).getMessage()
              .contains("at least one millisecond"),
          tooSmall.toString()
      );
    }
    // microsecond granularity is fine for the other strategies but not here
    final var tooFine = new BackoffConfig(
        BackoffStrategy.fibonacci, Duration.ofNanos(1_500_000), Duration.ofSeconds(13)
    );
    assertTrue(
        assertThrows(IllegalArgumentException.class, tooFine::createBackoff).getMessage()
            .contains("whole milliseconds")
    );
  }

  @Test
  void singleKeepsFullPrecisionAndAllowsNoDelay() {
    // a single backoff is a constant pause: nothing steps through units, and an
    // immediate retry stays expressible
    final var immediate = createBackoff(BackoffStrategy.single, Duration.ZERO, Duration.ofSeconds(1));
    assertEquals(TimeUnit.NANOSECONDS, immediate.timeUnit());
    assertEquals(0, immediate.delay(1, TimeUnit.NANOSECONDS));

    final var subMicro = createBackoff(BackoffStrategy.single, Duration.ofNanos(500), Duration.ofSeconds(1));
    assertEquals(500, subMicro.delay(1, TimeUnit.NANOSECONDS));
  }

  @Test
  void parsedConfigsBuildTheirBackoff() {
    final var properties = new Properties();
    properties.setProperty("strategy", "fibonacci");
    properties.setProperty("initialRetryDelay", "PT1S");
    properties.setProperty("maxRetryDelay", "PT13S");
    final var parsed = BackoffConfig.parse(properties).createBackoff();
    assertEquals(TimeUnit.SECONDS, parsed.timeUnit());
    assertEquals(1, parsed.delay(1, TimeUnit.SECONDS));

    // the defaults an unset config falls back to remain buildable
    final var defaults = BackoffConfig.parseConfig(JsonIterator.parse("{}")).createBackoff();
    assertEquals(TimeUnit.SECONDS, defaults.timeUnit());
    assertEquals(1, defaults.delay(1, TimeUnit.SECONDS));
    assertEquals(32, defaults.delay(Integer.MAX_VALUE, TimeUnit.SECONDS));
  }
}
