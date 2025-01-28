# Sava Core Services

Provides a collection of components to build durable networked services.

## Configuration Primitives

### Time

Durations/windows/delays are [ISO-8601 duration](https://en.wikipedia.org/wiki/ISO_8601#Durations) formatted
`PnDTnHnMn.nS`,  `PT` may be omitted.

## Components

### [Request Capacity Monitor](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/request_capacity/ErrorTrackedCapacityMonitor.java)

Tracks request capacity available to a resource. Works in conjunction with [Calls](#Call) to potentially wait until
capacity is available before making a request to avoid rate-limiting issues. Capacity is reduced in a weighted fashion
per call, and also potentially when response errors are observed.

#### [Configuration](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/request_capacity/CapacityConfig.java)

* `maxCapacity`: Maximum requests that can be made within `resetDuration`
* `resetDuration`: `maxCapacity` is added over the course of this duration.
* `minCapacityDuration`: Maximum time before capacity should recover to a positive value, given that no additional
  failures happen.
* `maxGroupedErrorResponses`:
* `maxGroupedErrorExpiration`:
* `tooManyErrorsBackoffDuration`:
* `serverErrorBackOffDuration`: Reduce capacity if remove server errors are observed.
* `rateLimitedBackOffDuration`: Reduce capacity if rate limit errors are observed.

### [Backoff](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/remote/call/Backoff.java)

Backoff strategy in response to I/O errors.

#### [Configuration](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/remote/call/BackoffConfig.java)

* `type`: single, linear, fibonacci, exponential
* `initialRetryDelay`
* `maxRetryDelay`

### [Call](https://github.com/sava-software/services/blob/main/core/src/main/java/software/sava/services/core/remote/call/Call.java)
