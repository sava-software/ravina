# Address Lookup Table Service

Moved to https://github.com/glamsystems/look

# Service Components

## [WebSocketManager](https://github.com/sava-software/ravina/blob/main/solana/src/main/java/software/sava/services/solana/websocket/WebSocketManager.java)

Used to manage the connection of a SolanaRpcWebsocket. Call `checkConnection` to drive re-connections. If errors are
observed, the corresponding delay from the
provided [Backoff](https://github.com/sava-software/ravina/blob/main/core/README.md#backoff) will be respected.

### Pseudo Usage

```
var backoff = Backoff.exponential(1, 32);
var account = PublicKey.fromBase58Encoded("");

var httpClient = HttpClient.newHttpClient();
var webSocketManager = WebSocketManager.createManager(
  httpClient,
  URI.create("wss://url"),
  backoff,
  webSocket -> webSocket.accountSubscribe(account, System.out::println)
);  
    
for (;;) {
  webSocketManager.checkConnection();
  Thread.sleep(1_000);
}
```

## [ChainItemFormatter](https://github.com/sava-software/services/blob/main/solana/src/main/java/software/sava/services/solana/config/ChainItemFormatter.java)

Used to provide more convenient logging of accounts and transaction hashes.

### Defaults & JSON Configuration

```json
{
  "sig": "https://solscan.io/tx/%s",
  "address": "https://solscan.io/account/%s"
}
```

### [WebHookClient](https://github.com/sava-software/services/blob/main/solana/src/main/java/software/sava/services/net/http/WebHookClient.java)

Generic client to POST JSON messages.

#### [JSON Configuration](https://github.com/sava-software/services/blob/main/solana/src/main/java/software/sava/services/net/http/WebHookConfig.java)

```json
[
  {
    "endpoint": "https://hooks.slack.com/services/...",
    "bodyFormat": "{\"text\":\"%s\"}",
    "capacity": {
      "maxCapacity": 2,
      "resetDuration": "1S",
      "minCapacityDuration": "8S"
    }
  }
]
```

```json
[
  {
    "endpoint": "https://hooks.slack.com/services/...",
    "provider": "SLACK",
    "capacity": {
      "maxCapacity": 2,
      "resetDuration": "1S",
      "minCapacityDuration": "8S"
    }
  }
]
```
