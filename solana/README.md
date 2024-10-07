# Address Lookup Table Service

## REST API

### Discover Tables

Attempts to find one or more tables to help minimize the size of a transaction.

#### `/v0/alt/discover/*`

Common parameters for discovery endpoints:

* **query**:
    * **accountsOnly**:
        * `true`: An array of base58 encoded lookup table public keys will be returned.
        * `false`: (default) An array of objects including both the table public key and the base64 encoded program
          account will be returned.
    * **reRank**:
        * `true`: Re-ranks each table after each top table found, trades off performance for potentially finding a
          better set of tables. If looking to improve an existing versioned transaction it is recommended to set this to
          true.
        * `false`: (default)

#### POST `/v0/alt/discover/tx/raw`

Post a serialized and encoded, legacy or v0, transaction.

```shell
curl -H "X-BYTE-ENCODING: base64" -d 'AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAcJDPVl6eB0qtYSlYif4b0tHW4ZfMrzSctd89y3PLhgsgb5JSlSZ9949Yv51O5NL2l3MVmpE3aLBgO3xqjQetH9/VRfow6jvD88KWbai2w9/vjTq32lfKAjKlTkoCZP/8PCCVTbvp7JYMmKeik/4hM2lm/hgNFRrkuBeVYfiYVKU/bMt8aVjwPz6dRn5EI8Gsat6wewXzwVooLo4DMVwF9Wd5cdDKvImMwegHYSrqlRr4mPm/gqRPWD+8llAWp4/D4KbwB9xBeu8gamlEHq3LaZuMqqSvkDUq1wkM++qfgfpGsr1lQqaOSFYS9WELcT14N7mJY9eLJbJXlsZ9Z5/AUPNko+70sDyCpxWZ6gehbuS89tzjE1fYRgsqwb1MOphgydAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAwgIAQAEBQIHBihFoV3KeH5MuQEBAAAA4fUFAAAAAADh9QUAAAAAAAAAAQAAAAAAAAAA' \
  'http://localhost:4242/v0/alt/discover/tx/raw?accountsOnly=true'; 
```

* **headers**:
    * **X-BYTE-ENCODING**: Encoding of the posted transaction.
        * `hex`
        * `base64`: default
* **query**:
    * **stats**:
        * `true`: Include stats comparing the input tx and the resulting versioned tx, as well as stats per table used
          to help get an idea of the impact.
        * `false`: (default)
    * **includeProvidedTables**: Only applicable if the query is a versioned transaction with lookup tables.
        * `true`: Include the table(s) from the queried transaction regardless. Otherwise, they might not exist in the
          local discovery cache.
        * `false`: (default). Can be useful to find a fresh view of the world.
* **body**: serialized and encoded transaction.

#### POST `/v0/alt/discover/accounts`

If you know the set of accounts used in your transaction(s) this endpoint may be more useful or easier to use than
posting a transaction.

Note: do not include invoked program accounts or signers.

```shell
curl -d '["8UJgxaiQx5nTrdDgph5FiahMmzduuLTLf5WmsPegYA6W","2UZMvVTBQR9yWxrEdzEQzXWE61bUjqQ5VpJAGqVb3B19","25Eax9W8SA3wpCQFhJEGyHhQ2NDHEshZEDzyMNtthR8D","7QAtMC3AaAc91W4XuwYXM1Mtffq9h9Z8dTxcJrKRHu1z"]' \
  'http://localhost:4242/v0/alt/discover/accounts?accountsOnly=true';
```

* **body**: JSON array of base58 encoded accounts.

## Service Configuration

### Example

Reference the documentation below for anything that is not implicitly clear and needs context.

```json
{
  "discovery": {
    "cacheOnly": false,
    "cacheDirectory": "/Users/user/src/services/solana/alt_cache",
    "clearCache": false,
    "remoteLoad": {
      "minUniqueAccountsPerTable": 34,
      "minTableEfficiency": 0.8,
      "maxConcurrentRequests": 16,
      "reloadDelay": "PT8h"
    },
    "query": {
      "numPartitions": 8,
      "topTablesPerPartition": 16,
      "startingMinScore": 8
    }
  },
  "web": {
    "port": 4242
  },
  "tableCache": {
    "initialCapacity": 256,
    "refreshStaleItemsDelay": "PT4h",
    "consideredStale": "PT8h"
  },
  "rpc": {
    "callWeights": {
      "getProgramAccounts": 2
    },
    "defaultCapacity": {
      "minCapacityDuration": "PT8S",
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
          "minCapacityDuration": "PT5S",
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

The service loads all active lookup tables stored on chain, and does so in 257 partitions. 256 based on the first byte
of the table authority, and one for tables with no authority (frozen).

While fetching remote tables it filters out subjectively non-useful tables using the discovery parameters documented
below.

Once all tables are retrieved they are joined into a single array.

Per query there is a parallel score/map and reduce step.

Scoring a table represents how many indexable accounts from the query exist in the table.

* **cacheOnly**: Does not remotely load lookup tables from RPC nodes.
* **cacheDirectory**: Binary files of lookup tables will be stored here. This allows the server to bootstrap within a
  couple of seconds (local SSD).
* **clearCache**: Only to be used when there are breaking cache data model changes.
* **remoteLoad**: Parameters relevant to loading and filtering tables from remote RPC nodes.
    * **minUniqueAccountsPerTable**
    * **minTableEfficiency**: `numUniqueAccounts / numAccounts`
    * **maxConcurrentRequests**: Max number of partitions that can be fetched concurrently.
    * **reloadDelay**: `java.time.Duration` encoded delay between defensive fetching of all on-chain tables.
* **query**: Per query related parameters.
    * **numPartitions**: The initial task of scoring tables will be divided into this many parallel windows.
    * **topTablesPerPartition**: The number of top scored tables for each window/partition to return.
    * **startingMinScore**: Minimum score at the map/score step for a table to be eligible for reduction. If no tables
      meet the requirement is are found, the minimum score is divided two down to a minimum of two before giving up.

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

* **callWeights**: Default call weight is 1, use this to help match rate limits for specific RPC methods.
    * **getProgramAccounts**
* **defaultCapacity**:
    * **resetDuration**: `java.time.Duration` encoded window in which maxCapacity is re-added.
    * **maxCapacity**: Maximum requests that can be made within `resetDuration`
    * **minCapacityDuration**: Maximum time, encoded as a `java.time.Duration`, before capacity should recover to a
      positive value, given that no additional failures happen.
* **defaultBackoff**: Backoff strategy in response to errors.
    * **type**: `fibonacci`, `exponential`, `linear`
    * **initialRetryDelaySeconds**
    * **maxRetryDelaySeconds**
* **endpoints**: Array of RPC node configurations.
    * **url**
    * **capacity**: Overrides `defaultCapacity`.
    * **backoff**: Overrides `defaultBackoff`.

## Run Table Service

### [Docker](../Dockerfile)

#### Pull

[Pull the image from the GitHub Container registry.](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#pulling-container-images)

```shell
docker pull ghcr.io/sava-software/services/solana:latest
```

#### Build

Creates an Alpine based image which includes a minimal executable JVM.

First, [create a classic GitHub user access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic)
with read access to GitHub Packages.

```shell
export GITHUB_ACTOR=<YOUR_GITHUB_USERNAME>
export GITHUB_TOKEN=<YOUR_GITHUB_TOKEN_SECRET>

docker build \
    --secret type=env,id=GITHUB_ACTOR,env=GITHUB_ACTOR \
    --secret type=env,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    -t sava-software/services/solana:latest .
```

#### Create Lookup Table Cache Volume

Used to support faster restarts.

```shell
docker volume create sava-solana-table-cache

docker run --rm -it \
  --user root \
  --mount source=sava-solana-table-cache,target=/sava/.sava \
  --entrypoint=ash \
    sava-software/services/solana:latest
    
# Any disk writes needed at runtime will be stored within /sava/.sava
chown sava /sava/.sava && chgrp nogroup /sava/.sava

exit
```

#### Run

Mount your local service configuration file to `/sava/config.json`.

Make sure the port you expose matches the port in your configuration file.

Pass any JVM options you prefer to the container as well as the `-m module/main_class` you want to run.

Note: Prefix image name with ghcr.io/ if you pulled from GitHub.

```shell
docker run --rm \
  --name table_service \
  --memory 13g \
  --publish 80:4242 \
  --mount type=bind,source="$(pwd)"/solana/configs/LookupTableService.json,target=/sava/config.json,readonly \
  --mount source=sava-solana-table-cache,target=/sava/.sava/solana/table_cache \
    sava-software/services/solana:latest \
      -server -XX:+UseZGC -Xms7G -Xmx12G \
      -m "software.sava.solana_services/software.sava.services.solana.accounts.lookup.http.LookupTableWebService"
```

### [Script Runner](../runService.sh)

Compiles a minimal executable JVM and facilitates passing runtime arguments.

Add the following to your gradle.properties file:

```shell
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITUHUB_ACCESS_TOKEN
```

Or, export the following environment variables:

```shell
export GITHUB_ACTOR=<YOUR_GITHUB_USERNAME>
export GITHUB_TOKEN=<YOUR_GITUHUB_ACCESS_TOKEN>
```

Run the service:

```shell
./runService.sh \
  --simpleProjectName="solana" \
  --configFile="./solana/configs/LookupTableService.json" \
  --moduleName="software.sava.solana_services" \
  --mainClass="software.sava.services.solana.accounts.lookup.http.LookupTableWebService" \
  --jvmArgs="-server -XX:+UseZGC -Xms7G -Xmx13G" \
  --screen=0
```

## Requirements

- The latest generally available JDK. This project will continue to move to the latest and will not maintain
  versions released against previous JDK's.

## [Dependencies](programs/src/main/java/module-info.java)

- [JSON Iterator](https://github.com/comodal/json-iterator?tab=readme-ov-file#json-iterator)
- [sava-core](https://github.com/sava-software/sava)
- [sava-rpc](https://github.com/sava-software/sava)
- [solana-programs](https://github.com/sava-software/solana-programs)
- [anchor-src-gen](https://github.com/sava-software/anchor-src-gen)
- [anchor-programs](https://github.com/sava-software/anchor-programs)

### Add Dependency

Create
a [GitHub user access token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-personal-access-token-classic)
with read access to GitHub Packages.

Then add the following to your Gradle build script.

```groovy
repositories {
  maven {
    url = "https://maven.pkg.github.com/sava-software/sava"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
  maven {
    url = "https://maven.pkg.github.com/sava-software/solana-programs"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
  maven {
    url = "https://maven.pkg.github.com/sava-software/anchor-src-gen"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
  maven {
    url = "https://maven.pkg.github.com/sava-software/anchor-programs"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
  maven {
    url = "https://maven.pkg.github.com/sava-software/services"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
}

dependencies {
  implementation "software.sava:sava-core:$VERSION"
  implementation "software.sava:sava-rpc:$VERSION"
  implementation "software.sava:solana-programs:$VERSION"
  implementation "software.sava:anchor-src-gen:$VERSION"
  implementation "software.sava:anchor-programs:$VERSION"
  implementation "software.sava:core-services:$VERSION"
  implementation "software.sava:solana-services:$VERSION"
}
```