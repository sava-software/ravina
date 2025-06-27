# Ravina [![Build](https://github.com/sava-software/ravina/actions/workflows/gradle.yml/badge.svg)](https://github.com/sava-software/ravina/actions/workflows/gradle.yml) [![Release](https://github.com/sava-software/ravina/actions/workflows/release.yml/badge.svg)](https://github.com/sava-software/ravina/actions/workflows/release.yml)

Components to ease the development of both generic and Solana based services.

## Documentation

User documentation lives at [sava.software](https://sava.software/).

* [Dependency Configuration](https://sava.software/quickstart)
* [Ravina](https://sava.software/libraries/ravina)

## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

#### ~/.gradle/gradle.properties

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

```shell
./gradlew check
```
