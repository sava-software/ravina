package software.sava.services.solana.config;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.TxStatus;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalInt;
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

  @Test
  void testParseJsonUnknownFieldThrows() {
    final var json = """
        {
          "bogus": "https://explorer.solana.com/tx/%s"
        }
        """;
    assertThrows(IllegalStateException.class, () -> ChainItemFormatter.parseFormatter(JsonIterator.parse(json)));
  }

  @Test
  void testCreateDefault() {
    final var formatter = ChainItemFormatter.createDefault();
    assertNotNull(formatter);
    assertEquals(DEFAULT_SIG_FORMAT, formatter.sigFormat());
    assertEquals(DEFAULT_ADDRESS_FORMAT, formatter.addressFormat());
  }

  @Test
  void testFormatHelpers() {
    final var formatter = ChainItemFormatter.createDefault();
    assertEquals("https://solscan.io/tx/abc", formatter.formatSig("abc"));
    assertEquals("https://solscan.io/account/xyz", formatter.formatAddress("xyz"));
    final var publicKey = PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH]);
    assertEquals(
        "https://solscan.io/account/11111111111111111111111111111111",
        formatter.formatAddress(publicKey)
    );
  }

  @Test
  void testFormatSigStatusWithContext() {
    final var formatter = ChainItemFormatter.createDefault();
    final var status = new TxStatus(new Context(123, "1.18"), 456, OptionalInt.of(2), null, Commitment.CONFIRMED);
    final var formatted = formatter.formatSigStatus("sigX", status);
    assertTrue(formatted.contains("https://solscan.io/tx/sigX"), formatted);
    assertTrue(formatted.contains("context slot: 123"), formatted);
    assertTrue(formatted.contains("tx slot: 456"), formatted);
    assertTrue(formatted.contains("confirmations: 2"), formatted);
  }

  @Test
  void testFormatSigStatusWithoutContext() {
    final var formatter = ChainItemFormatter.createDefault();
    final var status = new TxStatus(null, 456, OptionalInt.empty(), null, null);
    final var formatted = formatter.formatSigStatus("sigX", status);
    assertTrue(formatted.contains("context slot: -1"), formatted);
    assertTrue(formatted.contains("tx slot: 456"), formatted);
    assertTrue(formatted.contains("confirmations: -1"), formatted);
  }

  @Test
  void testCommaSeparateInteger() {
    assertEquals("", ChainItemFormatter.commaSeparateInteger(""));
    assertEquals("1", ChainItemFormatter.commaSeparateInteger("1"));
    assertEquals("12", ChainItemFormatter.commaSeparateInteger("12"));
    assertEquals("123", ChainItemFormatter.commaSeparateInteger("123"));
    assertEquals("1,234", ChainItemFormatter.commaSeparateInteger("1234"));
    assertEquals("12,345", ChainItemFormatter.commaSeparateInteger("12345"));
    assertEquals("123,456", ChainItemFormatter.commaSeparateInteger("123456"));
    assertEquals("1,234,567", ChainItemFormatter.commaSeparateInteger("1234567"));
    assertEquals("98,765,432,109,876,543,210", ChainItemFormatter.commaSeparateInteger("98765432109876543210"));
  }
}
