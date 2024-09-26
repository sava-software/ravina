# Address Lookup Table Service

## Configuration

### Example Configuration

```json
{
  "discovery": {
    "cacheDirectory": "/Users/user/src/services/solana/alt_cache",
    "remoteLoad": {
      "minUniqueAccountsPerTable": 34,
      "minTableEfficiency": 0.8,
      "maxConcurrentRequests": 10,
      "reloadDelay": null
    },
    "query": {
      "numPartitions": 8,
      "topTablesPerPartition": 16,
      "minScore": 1
    }
  },
  "web": {
    "port": 4242
  },
  "tableCache": {
    "initialCapacity": 32000,
    "refreshStaleItemsDelay": "PT4h",
    "consideredStale": "PT8h"
  },
  "rpc": {
    "defaultCapacity": {
      "minCapacity": -16,
      "maxCapacity": 2,
      "resetDuration": "PT1S"
    },
    "defaultBackoff": {
      "type": "fibonacci",
      "initialRetryDelaySeconds": 1,
      "maxRetryDelaySeconds": 21
    },
    "endpoints": [
      {
        "url": "https://mainnet.helius-rpc.com/?api-key=YOUR_API_KEY",
        "capacity": {
          "minCapacity": -50,
          "maxCapacity": 50,
          "resetDuration": "PT1S"
        },
        "backoff": {
          "type": "exponential",
          "initialRetryDelaySeconds": 1,
          "maxRetryDelaySeconds": 16
        }
      },
      {
        "url": "https://solana-mainnet.rpc.extrnode.com/YOUR_API_KEY"
      }
    ]
  }
}
```

### discovery

The service loads all lookup tables stored on chain, and does so in 257 partitions. 256 based on the first byte of the
table authority, and one for tables with no authority (frozen).

While fetching remote tables it subjectively filters out non-useful tables using the discovery parameters documented
below.

Once all tables are retrieved they are joined into a single array. The size after filtering currently does not justify
creating indexes to support queries, however a parallel score/map and reduce does improve performance per query.

Scoring a table represents how my indexable accounts from the query exist in the table.

* **cacheDirectory**: Binary files of lookup tables will be stored here. This allows the server to bootstrap within a
  couple of seconds (local ssd).
* **remoteLoad**: Parameters relevant to loading tables from remote RPC nodes.
    * minUniqueAccountsPerTable
    * minTableEfficiency: `uniqueAccounts / numAccounts`
    * maxConcurrentRequests: Max number of partitions that can be fetched concurrently.
    * reloadDelay: `java.time.Duration` encoded delay between defensive fetching of all on-chain tables.
* **query**: Per query related parameters.
    * numPartitions: The initial task of scoring tables will be divided into this many parallel window.
    * topTablesPerPartition: The number of top scored tables for each window/partition to return.
    * minScore: Minimum score at the map/score step for a table to be eligible for reduction.

### web

* **port**: Port to bind to, defaults to 0, which will pick a randomly available port. The port the server is listening
  to will be logged to the terminal.

### tableCache

The table cache is used to support requests which send a versioned transaction. The intention would be to try to
re-optimize an existing versioned transaction with multiple lookup tables or if you would like to recover rent from a
redundant table account.

* initialCapacity: If you do not intend to send versioned transactions to this service, set this to 0. Otherwise, try to
  estimate how many unique table accounts will be encountered via queries.
* refreshStaleItemsDelay: `java.time.Duration` encoded delay between driving a refresh of the table cache.
* consideredStale: `java.time.Duration` encoded duration to define tables which are stale versus their on-chain state.
