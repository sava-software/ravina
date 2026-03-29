package software.sava.services.solana.config;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class ChainItemFormatterTests {

  private static final String DEFAULT_SIG_FORMAT = "https://solscan.io/tx/%s";
  private static final String DEFAULT_ADDRESS_FORMAT = "https://solscan.io/account/%s";

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "sig": "https://explorer.solana.com/tx/%s",
          "address": "https://explorer.solana.com/address/%s"
        }
        """;
    final var formatter = ChainItemFormatter.parseFormatter(JsonIterator.parse(json));
    assertEquals("https://explorer.solana.com/tx/%s", formatter.sigFormat());
    assertEquals("https://explorer.solana.com/address/%s", formatter.addressFormat());
  }

  @Test
  void testParseJsonSigOnly() {
    final var json = """
        {
          "sig": "https://explorer.solana.com/tx/%s"
        }
        """;
    final var formatter = ChainItemFormatter.parseFormatter(JsonIterator.parse(json));
    assertEquals("https://explorer.solana.com/tx/%s", formatter.sigFormat());
    assertEquals(DEFAULT_ADDRESS_FORMAT, formatter.addressFormat());
  }

  @Test
  void testParseJsonAddressOnly() {
    final var json = """
        {
          "address": "https://explorer.solana.com/address/%s"
        }
        """;
    final var formatter = ChainItemFormatter.parseFormatter(JsonIterator.parse(json));
    assertEquals(DEFAULT_SIG_FORMAT, formatter.sigFormat());
    assertEquals("https://explorer.solana.com/address/%s", formatter.addressFormat());
  }

  @Test
  void testParseJsonDefaults() {
    final var json = """
        {}
        """;
    final var formatter = ChainItemFormatter.parseFormatter(JsonIterator.parse(json));
    assertEquals(DEFAULT_SIG_FORMAT, formatter.sigFormat());
    assertEquals(DEFAULT_ADDRESS_FORMAT, formatter.addressFormat());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var formatter = ChainItemFormatter.parseFormatter(JsonIterator.parse(json));
    assertNull(formatter);
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("sig", "https://explorer.solana.com/tx/%s");
    properties.setProperty("address", "https://explorer.solana.com/address/%s");

    final var formatter = ChainItemFormatter.parseConfig(properties);
    assertEquals("https://explorer.solana.com/tx/%s", formatter.sigFormat());
    assertEquals("https://explorer.solana.com/address/%s", formatter.addressFormat());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var properties = new Properties();

    final var formatter = ChainItemFormatter.parseConfig(properties);
    assertEquals(DEFAULT_SIG_FORMAT, formatter.sigFormat());
    assertEquals(DEFAULT_ADDRESS_FORMAT, formatter.addressFormat());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("formatter.sig", "https://explorer.solana.com/tx/%s");
    properties.setProperty("formatter.address", "https://explorer.solana.com/address/%s");

    final var formatter = ChainItemFormatter.parseConfig("formatter", properties);
    assertEquals("https://explorer.solana.com/tx/%s", formatter.sigFormat());
    assertEquals("https://explorer.solana.com/address/%s", formatter.addressFormat());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("app.formatter.sig", "https://explorer.solana.com/tx/%s");
    properties.setProperty("app.formatter.address", "https://explorer.solana.com/address/%s");

    final var formatter = ChainItemFormatter.parseConfig("app.formatter.", properties);
    assertEquals("https://explorer.solana.com/tx/%s", formatter.sigFormat());
    assertEquals("https://explorer.solana.com/address/%s", formatter.addressFormat());
  }

  @Test
  void testParsePropertiesSigOnly() {
    final var properties = new Properties();
    properties.setProperty("sig", "https://explorer.solana.com/tx/%s");

    final var formatter = ChainItemFormatter.parseConfig(properties);
    assertEquals("https://explorer.solana.com/tx/%s", formatter.sigFormat());
    assertEquals(DEFAULT_ADDRESS_FORMAT, formatter.addressFormat());
  }
}
