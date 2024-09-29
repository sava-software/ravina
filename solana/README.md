# Address Lookup Table Service

## Call

### Discover Tables

Attempts to find one or more tables to help minimize the size of a transaction.

#### POST `/v0/alt/discover/tx/raw`

Post a serialized and encoded legacy or version zero transaction.

```shell
curl -d 'AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAcJDPVl6eB0qtYSlYif4b0tHW4ZfMrzSctd89y3PLhgsgb5JSlSZ9949Yv51O5NL2l3MVmpE3aLBgO3xqjQetH9/VRfow6jvD88KWbai2w9/vjTq32lfKAjKlTkoCZP/8PCCVTbvp7JYMmKeik/4hM2lm/hgNFRrkuBeVYfiYVKU/bMt8aVjwPz6dRn5EI8Gsat6wewXzwVooLo4DMVwF9Wd5cdDKvImMwegHYSrqlRr4mPm/gqRPWD+8llAWp4/D4KbwB9xBeu8gamlEHq3LaZuMqqSvkDUq1wkM++qfgfpGsr1lQqaOSFYS9WELcT14N7mJY9eLJbJXlsZ9Z5/AUPNko+70sDyCpxWZ6gehbuS89tzjE1fYRgsqwb1MOphgydAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAwgIAQAEBQIHBihFoV3KeH5MuQEBAAAA4fUFAAAAAADh9QUAAAAAAAAAAQAAAAAAAAAA' \
  'http://localhost:4242/v0/alt/tx/raw?accountsOnly=true'; 
```

* **headers**:
    * **X-BYTE-ENCODING**: Encoding of the posted transaction.
        * `hex`
        * `base64`: default
* **query**:
    * **accountsOnly**:
        * `true`: An array of base58 encoded lookup table public keys will be returned.
        * `false`: (default) An array of objects including both the table public key and the base64 encoded program
          account will
          be
          returned.
* **body**: serialized and encoded transaction.

#### POST `/v0/alt/discover/accounts`

If you know the set of accounts commonly used in your transactions this endpoint can be more useful than posting a
transaction.

Note: do not include invoked program accounts or signers.

```shell
curl -d '["8UJgxaiQx5nTrdDgph5FiahMmzduuLTLf5WmsPegYA6W","2UZMvVTBQR9yWxrEdzEQzXWE61bUjqQ5VpJAGqVb3B19","25Eax9W8SA3wpCQFhJEGyHhQ2NDHEshZEDzyMNtthR8D","7QAtMC3AaAc91W4XuwYXM1Mtffq9h9Z8dTxcJrKRHu1z"]' \
  'http://localhost:4242/v0/alt/accounts?accountsOnly=true';
```

* **query**:
    * **accountsOnly**:
        * `true`: An array of base58 encoded lookup table public keys will be returned.
        * `false`: (default) An array of objects including both the table public key and the base64 encoded program
          account will
          be
          returned.
* **body**: JSON array of base58 encoded accounts.

## Service Configuration

### Example

Reference the documentation below for anything that is not implicitly clear and needs context.

```json
{
  "discovery": {
    "cacheDirectory": "/Users/user/src/services/solana/alt_cache",
    "remoteLoad": {
      "minUniqueAccountsPerTable": 34,
      "minTableEfficiency": 0.8,
      "maxConcurrentRequests": 10,
      "reloadDelay": "PT8h"
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
    "initialCapacity": 4096,
    "refreshStaleItemsDelay": "PT4h",
    "consideredStale": "PT8h"
  },
  "rpc": {
    "defaultCapacity": {
      "minCapacity": -32,
      "maxCapacity": 4,
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

While fetching remote tables it filters out subjectively non-useful tables using the discovery parameters documented
below.

Once all tables are retrieved they are joined into a single array.

Per query there is a parallel score/map and reduce step.

Scoring a table represents how many indexable accounts from the query exist in the table.

* **cacheDirectory**: Binary files of lookup tables will be stored here. This allows the server to bootstrap within a
  couple of seconds (local SSD).
* **remoteLoad**: Parameters relevant to loading and filtering tables from remote RPC nodes.
    * **minUniqueAccountsPerTable**
    * **minTableEfficiency**: `numUniqueAccounts / numAccounts`
    * **maxConcurrentRequests**: Max number of partitions that can be fetched concurrently.
    * **reloadDelay**: `java.time.Duration` encoded delay between defensive fetching of all on-chain tables.
* **query**: Per query related parameters.
    * **numPartitions**: The initial task of scoring tables will be divided into this many parallel windows.
    * **topTablesPerPartition**: The number of top scored tables for each window/partition to return.
    * **minScore**: Minimum score at the map/score step for a table to be eligible for reduction.

### web

Web Server parameters.

* **port**: Port to bind to, defaults to 0, which will pick a randomly available port. The port the server is listening
  to will be logged.

### tableCache

The table cache is used to support requests which send a versioned transaction. The intention would be to try to
re-optimize an existing versioned transaction with multiple lookup tables or if you would like to recover rent from a
redundant table account.

* **initialCapacity**: If you do not intend to send versioned transactions to this service, set this to 0. Otherwise,
  try to
  estimate how many unique table accounts will be encountered via queries.
* **refreshStaleItemsDelay**: `java.time.Duration` encoded delay between driving a refresh of the table cache.
* **consideredStale**: `java.time.Duration` encoded duration to define tables which are stale versus their on-chain
  state.

### rpc

RPC nodes.

* **defaultCapacity**:
    * **resetDuration**: `java.time.Duration` encoded window in which maxCapacity is re-added.
    * **maxCapacity**: Maximum requests that can be made within `resetDuration`
    * **minCapacity**: Minimum negative capacity. Capacity is added in proportion to `maxCapacity` per
      `resetDuration` to allow it to eventually recover.
* **defaultBackoff**: Backoff strategy in response to errors.
    * **type**: `fibonacci`, `exponential`, `linear`
    * **initialRetryDelaySeconds**
    * **maxRetryDelaySeconds**
* **endpoints**: Array of RPC node configurations.
    * **url**
    * **capacity**: Overrides `defaultCapacity`.
    * **backoff**: Overrides `defaultBackoff`.

## Run Table Service

### Docker

#### Build

jlink is used to create an executable JVM which contains the minimal set of modules.

```shell
docker build -t lookup_table_service:latest .
```

#### Create Lookup Table Cache Volume

Used to support faster restarts.

```shell
docker volume create sava-solana-table-cache

docker run --rm -it \
  --user root \
  --mount source=sava-solana-table-cache,target=/sava/.sava \
  --entrypoint=ash \
    lookup_table_service:latest
# Any disk writes needed at runtime will be stored within /sava/.sava
chown sava /sava/.sava && chgrp nogroup /sava/.sava
exit
```

#### Run

Mount your local service configuration file to `/sava/config.json`.

Make sure the port you expose matches the port in your configuration file.

Pass any JVM options you prefer to the container as well as the `-m module/main_class` you want to run.

```shell
docker run --rm \
  --name table_service \
  --memory 13g \
  --publish 4242:4242 \
  --mount type=bind,source="$(pwd)"/solana/configs/LookupTableService.json,target=/sava/config.json,readonly \
  --mount source=sava-solana-table-cache,target=/sava/.sava/solana/table_cache \
    lookup_table_service:latest \
      -server -XX:+UseZGC -Xms7G -Xmx12G \
      -m "software.sava.solana_services/software.sava.services.solana.accounts.lookup.http.LookupTableWebService"
```

### Script Runner

Compiles a minimal executable JVM and facilitates passing runtime arguments.

```shell
./runService.sh \
  --simpleProjectName="solana" \
  --configFile="./solana/configs/LookupTableService.json" \
  --moduleName="software.sava.solana_services" \
  --mainClass="software.sava.services.solana.accounts.lookup.http.LookupTableWebService" \
  --jvmArgs="-server -XX:+UseZGC -Xms8G -Xmx13G"
```