# Changelog

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
