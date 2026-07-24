# Changelog

## [25.5.2](https://github.com/sava-software/ravina/compare/25.5.1...25.5.2) (2026-07-24)


### ⚠ BREAKING CHANGES

* configurations that relied on silent truncation — sub-unit backoff delays, sub-millisecond capacity reset durations or monitor delays, a slot sample window under 60 seconds — now throw IllegalArgumentException at parse/build time.

### Bug Fixes

* **fuzz:** commit configs seed corpora hidden by a bare gitignore pattern ([02ce084](https://github.com/sava-software/ravina/commit/02ce084a2085b7a7d236f2e6d55e8eca40a30b62))
* validate config durations and restore classpath service discovery ([78b1a3c](https://github.com/sava-software/ravina/commit/78b1a3cd453b984768bd7519c3a7be6b3d5d310a))

## [25.5.1](https://github.com/sava-software/ravina/compare/25.5.0...25.5.1) (2026-07-21)


### Bug Fixes

* **backoff:** Check for delay overflow on fibonacci backoff ([e88e05b](https://github.com/sava-software/ravina/commit/e88e05b6814a5ac384feabc335894f9e8a5a052b))
* **backoff:** Check for delay overflow on fibonacci backoff ([dac8472](https://github.com/sava-software/ravina/commit/dac84727b7bd7e112fd2b1194d565f3f41fb578b))
* **hardening:** Sync latest hardening context. ([a8acb6e](https://github.com/sava-software/ravina/commit/a8acb6ef70588dcd53380d4e6a6f2d667c8629dc))

## [25.5.0](https://github.com/sava-software/ravina/compare/25.4.1...25.5.0) (2026-07-21)


### ⚠ BREAKING CHANGES

* ErrorTracker extends BiPredicate<R, byte[]>; implementors must update test(), logResponse() and updateGroupedErrorResponseCount() to accept the response body. HTTP capacity monitors and client configs are now typed on HttpResponse<?> instead of HttpResponse<byte[]>. HeliusClient and its request and response types moved from software.sava.solana.web2.helius.client.http to software.sava.services.solana.helius.client.http.

### Features

* migrate to sava 25.28.0 and vendor the Helius client ([9d92e53](https://github.com/sava-software/ravina/commit/9d92e53f5ebc289a6ba0be672c6cfe7c129e6c3c))
* **tests:** add extensive unit tests for balanced calls and backoff strategies ([5fc99e6](https://github.com/sava-software/ravina/commit/5fc99e6d3d81a6817f3866825e2b756903830a0b))


### Bug Fixes

* Use NanoClock in RootErrorTracker instead of System.currentTimeMillis() ([216cf77](https://github.com/sava-software/ravina/commit/216cf771056f6d4b24d5508fe19d632926338606))


### Miscellaneous Chores

* release 25.5.0 ([90c90a5](https://github.com/sava-software/ravina/commit/90c90a502256f8d5f32c09271e2a8e90e84d75d0))

## [25.4.1](https://github.com/sava-software/ravina/compare/25.4.0...25.4.1) (2026-07-17)


### Bug Fixes

* **gradle:** correct task dependency names in GitHub publishing script ([ff9b55c](https://github.com/sava-software/ravina/commit/ff9b55ce43ecdbe483e648e15f8963bfa663a04b))

## [25.4.0](https://github.com/sava-software/ravina/compare/25.3.0...25.4.0) (2026-07-17)


### ⚠ BREAKING CHANGES

* **core:** JSON parsing implementations now rely on `FieldMatcher`. Ensure proper mapping of field names in all JSON parsing logic.
* **core:** Core modules now enforce stricter validation in fuzz-tested components. Update any code depending on lenient parsing or unchecked delays.
* **core:** Time-based operations now require `NanoClock` instead of direct use of system time. Update constructors and method calls to provide a `NanoClock` instance.

### Features

* **core:** add fuzz tests for backoff, capacity, and load balancing ([af72c32](https://github.com/sava-software/ravina/commit/af72c32063145585d8196807de36aeabf9c2b9b0))
* **core:** add NanoClock interface for time abstraction ([f48d39e](https://github.com/sava-software/ravina/commit/f48d39ec2888e4a7a4332c7ef3152163c1085f8d))
* **tests:** add extensive unit and fuzz tests for calls and configs ([42a333b](https://github.com/sava-software/ravina/commit/42a333bb6c145c46c304441e5ea3464abbecffc8))


### Miscellaneous Chores

* release 25.4.0 ([cc93fe0](https://github.com/sava-software/ravina/commit/cc93fe03fb9406d7edda60a17f7f9415b58baa09))


### Code Refactoring

* **core:** replace fieldEquals with FieldMatcher for JSON parsing ([1b3768a](https://github.com/sava-software/ravina/commit/1b3768a89ca12cac9632798f4f31d9409e0bd581))

## [25.3.0](https://github.com/sava-software/ravina/compare/25.2.2...25.3.0) (2026-06-01)


### ⚠ BREAKING CHANGES

* **solana:** The `software.sava.solana_programs` library has been removed in favor of `idl-clients`. Update affected imports and configurations accordingly.

### Miscellaneous Chores

* release 25.3.0 ([2f4d755](https://github.com/sava-software/ravina/commit/2f4d755e8c6706d704ea19a9c028785c72fad8aa))


### Code Refactoring

* **solana:** replace solana-programs with idl-clients modules ([b03a758](https://github.com/sava-software/ravina/commit/b03a758dbfb36aa549f67408c642709e2e8c8f8c))

## [25.2.2](https://github.com/sava-software/ravina/compare/25.2.1...25.2.2) (2026-05-30)


### ⚠ BREAKING CHANGES

* **build:** Solana spring and jetty modules are no longer available. Migrate any dependent functionality outside the repository or use alternative solutions.

### Code Refactoring

* **build:** remove unused solana spring and jetty modules ([ac1555f](https://github.com/sava-software/ravina/commit/ac1555fa686639403695d9da2bb7b22b439a3167))
