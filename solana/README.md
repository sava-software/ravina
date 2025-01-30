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

## [EpochInfoService](https://github.com/sava-software/ravina/blob/main/solana/src/main/java/software/sava/services/solana/epoch/EpochInfoService.java)

Periodically polls for slot performance stats and epoch information to provide
an [Epoch data record](https://github.com/sava-software/ravina/blob/main/solana/src/main/java/software/sava/services/solana/epoch/Epoch.java). 

The Epoch data record provides the following estimation features:
* Duration remaining for a given time unit.
* Percent complete.
* Epochs per year.
* The current slot index.
* The current block height accounting for a skip rate.
* [SlotPerformanceStats](https://github.com/sava-software/ravina/blob/main/solana/src/main/java/software/sava/services/solana/epoch/SlotPerformanceStats.java)

### [Configuration](https://github.com/sava-software/ravina/blob/main/solana/src/main/java/software/sava/services/solana/epoch/EpochServiceConfig.java)

* `defaultMillisPerSlot`: Used if performance samples are not available.
* `minMillisPerSlot`: Performance samples will be capped >= this value.
* `maxMillisPerSlot`: Performance samples will be capped <= this value.
* `slotSampleWindow`: The recent time window to draw performance samples.
* `fetchSlotSamplesDelay`: Poll new performance samples and epoch information delay.
* `fetchEpochInfoAfterEndDelay`: Also fetch new performance samples and epoch information after each epoch rollover.

```json
{
  "defaultMillisPerSlot": 405,
  "minMillisPerSlot": 400,
  "maxMillisPerSlot": 500,
  "slotSampleWindow": "13M",
  "fetchSlotSamplesDelay": "8M",
  "fetchEpochInfoAfterEndDelay": "0.5S"
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
