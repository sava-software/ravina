# Sava Spring Boot Auto Wiring

This repo is intended to provide reference code for auto-wiring a set of load balanced Solana RPC clients in your Spring
Boot application. With the intention that it can be adapted to your preferred Spring Boot environment and configuration
property wiring preferences.

## [Application Properties YAML](config/application.yaml)

Configures a list of RPC endpoints and their respective request capacity and back-off strategies. Each endpoint entry
must have a valid endpoint RPC URL, as well as a capacity and backoff configuration, either directly or via the
defaults.

The following configuration corresponds to the four following properties classes:

* **[LoadBalancerFactory](src/main/java/software/sava/services/spring/solana/LoadBalancerFactory.java)**
* **[RemoteResourceProperties](src/main/java/software/sava/services/spring/solana/RemoteResourceProperties.java)**
* **[CapacityConfigProperties](src/main/java/software/sava/services/spring/solana/CapacityConfigProperties.java)**
* **[BackOffProperties](src/main/java/software/sava/services/spring/solana/BackOffProperties.java)**

### Capacity

Used to set the rate limit for each resource.

### Backoff

Used to back-off in response to errors or rate-limiting. If the capacity is configured and used correctly, rate-limiting
should not be an issue.

#### **backoffStrategy**

* exponential
* fibonacci
* linear
* single

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

### Run

```shell
./gradlew --console=plain -q :solana_spring:runWebServer
```