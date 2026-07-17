# Changelog

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
