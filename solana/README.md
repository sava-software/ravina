# Address Lookup Table Service

Moved to https://github.com/glamsystems/look

# Sava Solana Service Components

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
