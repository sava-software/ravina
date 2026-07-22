package software.sava.services.solana.load_balance;

import org.junit.jupiter.api.Test;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.remote.load_balance.LoadBalancerConfig;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Building an [software.sava.rpc.json.http.client.SolanaRpcClient] performs no
/// I/O, so the whole balancer can be constructed and inspected offline. No
/// request is ever issued against the configured endpoints.
final class LoadBalanceUtilTests {

  private static final String CONFIG = """
      {
        "defaultCapacity": {
          "minCapacity": 0,
          "maxCapacity": 100,
          "resetDuration": "PT10S"
        },
        "defaultBackoff": {
          "strategy": "exponential",
          "initialRetryDelay": "PT1S",
          "maxRetryDelay": "PT60S"
        },
        "endpoints": [
          "https://rpc1.example.com",
          "https://rpc2.example.com"
        ]
      }
      """;

  @Test
  void everyConfiguredEndpointBecomesABalancedClient() {
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(CONFIG));
    assertEquals(2, config.resourceConfigs().size());

    try (final var httpClient = HttpClient.newHttpClient()) {
      final var balancer = LoadBalanceUtil.createRPCLoadBalancer(config, httpClient);

      assertNotNull(balancer);
      assertEquals(2, balancer.size());

      final var items = balancer.items();
      assertEquals(2, items.size());
      for (final var item : items) {
        assertNotNull(item);
        assertNotNull(item.item());
        assertNotNull(item.capacityMonitor());
        assertNotNull(item.backoff());
      }

      final var endpoints = items.stream()
          .map(BalancedItem::item)
          .map(client -> client.endpoint().toString())
          .sorted()
          .toList();
      assertEquals(List.of("https://rpc1.example.com", "https://rpc2.example.com"), endpoints);

      // Every client is built on the injected HttpClient, not a default one.
      for (final var item : items) {
        assertSame(httpClient, item.item().httpClient());
      }

      // The balancer hands out every configured client.
      assertNotNull(balancer.next());
      assertNotNull(balancer.next());
    }
  }

  @Test
  void aSingleEndpointStillYieldsAUsableBalancer() {
    final var json = """
        {
          "defaultCapacity": {"minCapacity": 0, "maxCapacity": 100, "resetDuration": "PT10S"},
          "defaultBackoff": {"strategy": "single", "initialRetryDelay": "PT1S", "maxRetryDelay": "PT1S"},
          "endpoints": ["https://solo.example.com"]
        }
        """;
    final var config = LoadBalancerConfig.parse(JsonIterator.parse(json));

    try (final var httpClient = HttpClient.newHttpClient()) {
      final var balancer = LoadBalanceUtil.createRPCLoadBalancer(config, httpClient);

      assertNotNull(balancer);
      assertEquals(1, balancer.size());
      final var client = balancer.next();
      assertNotNull(client);
      assertEquals(URI.create("https://solo.example.com"), client.endpoint());
    }
  }
}
