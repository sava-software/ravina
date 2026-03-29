package software.sava.services.solana.remote.call;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class CallWeightsTests {

  @Test
  void testParseJsonAllFields() {
    final var json = """
        {
          "getProgramAccounts": 4,
          "getTransaction": 8,
          "sendTransaction": 20
        }
        """;
    final var callWeights = CallWeights.parse(JsonIterator.parse(json));
    assertEquals(4, callWeights.getProgramAccounts());
    assertEquals(8, callWeights.getTransaction());
    assertEquals(20, callWeights.sendTransaction());
  }

  @Test
  void testParseJsonDefaults() {
    final var json = "{}";
    final var callWeights = CallWeights.parse(JsonIterator.parse(json));
    assertEquals(2, callWeights.getProgramAccounts());
    assertEquals(5, callWeights.getTransaction());
    assertEquals(10, callWeights.sendTransaction());
  }

  @Test
  void testParseJsonPartial() {
    final var json = """
        {
          "getTransaction": 12
        }
        """;
    final var callWeights = CallWeights.parse(JsonIterator.parse(json));
    assertEquals(2, callWeights.getProgramAccounts());
    assertEquals(12, callWeights.getTransaction());
    assertEquals(10, callWeights.sendTransaction());
  }

  @Test
  void testParseJsonNull() {
    final var json = "null";
    final var callWeights = CallWeights.parse(JsonIterator.parse(json));
    assertNull(callWeights);
  }

  @Test
  void testCreateDefault() {
    final var callWeights = CallWeights.createDefault();
    assertEquals(2, callWeights.getProgramAccounts());
    assertEquals(5, callWeights.getTransaction());
    assertEquals(10, callWeights.sendTransaction());
  }

  @Test
  void testParsePropertiesAllFields() {
    final var properties = new Properties();
    properties.setProperty("getProgramAccounts", "3");
    properties.setProperty("getTransaction", "7");
    properties.setProperty("sendTransaction", "15");
    final var callWeights = CallWeights.parseConfig(properties);
    assertEquals(3, callWeights.getProgramAccounts());
    assertEquals(7, callWeights.getTransaction());
    assertEquals(15, callWeights.sendTransaction());
  }

  @Test
  void testParsePropertiesDefaults() {
    final var callWeights = CallWeights.parseConfig(new Properties());
    assertEquals(2, callWeights.getProgramAccounts());
    assertEquals(5, callWeights.getTransaction());
    assertEquals(10, callWeights.sendTransaction());
  }

  @Test
  void testParsePropertiesWithPrefix() {
    final var properties = new Properties();
    properties.setProperty("weights.getProgramAccounts", "6");
    properties.setProperty("weights.getTransaction", "10");
    properties.setProperty("weights.sendTransaction", "25");
    final var callWeights = CallWeights.parseConfig("weights", properties);
    assertEquals(6, callWeights.getProgramAccounts());
    assertEquals(10, callWeights.getTransaction());
    assertEquals(25, callWeights.sendTransaction());
  }

  @Test
  void testParsePropertiesWithDottedPrefix() {
    final var properties = new Properties();
    properties.setProperty("rpc.weights.getProgramAccounts", "1");
    properties.setProperty("rpc.weights.getTransaction", "2");
    properties.setProperty("rpc.weights.sendTransaction", "3");
    final var callWeights = CallWeights.parseConfig("rpc.weights.", properties);
    assertEquals(1, callWeights.getProgramAccounts());
    assertEquals(2, callWeights.getTransaction());
    assertEquals(3, callWeights.sendTransaction());
  }

  @Test
  void testParsePropertiesPartial() {
    final var properties = new Properties();
    properties.setProperty("sendTransaction", "50");
    final var callWeights = CallWeights.parseConfig(properties);
    assertEquals(2, callWeights.getProgramAccounts());
    assertEquals(5, callWeights.getTransaction());
    assertEquals(50, callWeights.sendTransaction());
  }
}
