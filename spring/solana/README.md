# Sava Spring Boot Auto Wiring

This repo is intended to provide reference code for auto-wiring a set of load balanced Solana RPC clients in your Spring
Boot application. With the intention that it can be adapted to your preferred Spring Boot environment and configuration
property wiring preferences.

## [Application Properties YAML](config/application.yaml)

Configures a list of RPC endpoints and their respective request capacity and back-off strategies. Each endpoint entry
must have a valid endpoint RPC URL, as well as a capacity and backoff configuration, either directly or via the
defaults.

The YAML configuration below corresponds to these four properties classes:

* **[LoadBalancerFactory](src/main/java/software/sava/services/spring/solana/LoadBalancerFactory.java)**:
  Root `sava-rpc-balanced` properties and LoadBalancer Bean.
* **[RemoteResourceProperties](src/main/java/software/sava/services/spring/solana/RemoteResourceProperties.java)**:
  Corresponds to each endpoint entry.
* **[CapacityConfigProperties](src/main/java/software/sava/services/spring/solana/CapacityConfigProperties.java)**
* **[BackOffProperties](src/main/java/software/sava/services/spring/solana/BackOffProperties.java)**

### Capacity

Used to set the rate limit for each resource.

### Backoff

Used to back-off in response to errors or rate-limiting. If the capacity is configured and used correctly, rate-limiting
should not be an issue.

```yaml
sava-rpc-balanced:
  endpoints:
    - endpoint: "https://api.mainnet-beta.solana.com"
      capacity:
        minCapacityDuration: 5
        maxCapacity: 1
        resetDuration: 2
      backoff:
        backoffStrategy: "exponential"
        initialRetryDelaySeconds: 1
        maxRetryDelaySeconds: 16
  defaultCapacity:
    minCapacityDuration: 8
    maxCapacity: 1
    resetDuration: 1
  defaultBackoff:
    backoffStrategy: "fibonacci"
    initialRetryDelaySeconds: 1
    maxRetryDelaySeconds: 13
```

## [Example Web Server](src/main/java/software/sava/services/spring/solana/SavaSpringBoot.java)

### Setup

In addition to the yaml configuration, a GitHub access token with read access to the package repository is needed.

```shell
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=YOUR_GITUHUB_ACCESS_TOKEN
```

Or, export the following environment variables:

```shell
export GITHUB_ACTOR=<YOUR_GITHUB_USERNAME>
export GITHUB_TOKEN=<YOUR_GITUHUB_ACCESS_TOKEN>
```

### Run

```shell
./gradlew --console=plain -q :solana_spring:runWebServer
```

### API

#### GET `/api/v0/accounts/token/owner/{owner}`

Returns the owners token accounts addresses with their mints and scaled amounts.

```shell
curl 'http://localhost:8080/api/v0/accounts/token/owner/<SOLANA_ADDRESS>';
```

```json
[
  {
    "address": "",
    "mint": "",
    "amount": 0
  }
]
```