# Sava Services Core

Provides a collection of components to build durable networked services.

## Configuration Primitives

### Time

Durations/windows/delays are [ISO-8601 duration](https://en.wikipedia.org/wiki/ISO_8601#Durations) formatted
`PnDTnHnMn.nS`,  `PT` may be omitted.

## Components 

### Request Capacity

Request capacity limits for constrained resources.  May be configured and constructed via [CapacityConfig](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/request_capacity/CapacityConfig.java)

* `maxCapacity`: Maximum requests that can be made within `resetDuration`
* `resetDuration`: `maxCapacity` is added over the course of this duration.
* `minCapacityDuration`: Maximum time before capacity should recover to a positive value, given that no additional
  failures happen.
* `maxGroupedErrorResponses`:
* `maxGroupedErrorExpiration`:
* `tooManyErrorsBackoffDuration`:
* `serverErrorBackOffDuration`: Reduce capacity if remove server errors are observed.
* `rateLimitedBackOffDuration`: Reduce capacity if rate limit errors are observed.

### Backoff

Backoff strategy in response to I/O errors.  May be configured and constructed via [BackoffConfig](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/remote/call/BackoffConfig.java)

* `type`: single, linear, fibonacci, exponential
* `initialRetryDelay`
* `maxRetryDelay`
