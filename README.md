# Ravina

Ravina provides components to ease the development of both generic and Solana based services.

## [Core](core/README.md)

Collection of components for building durable networked services.

## [Solana](solana/README.md)

Collection of services and service components for building Solana specific services.

## Dependency Configuration

### GitHub Access Token

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

### Gradle Build

```groovy
repositories {
  maven {
    url = "https://maven.pkg.github.com/sava-software/ravina"
    credentials {
      username = GITHUB_USERNAME
      password = GITHUB_PERSONAL_ACCESS_TOKEN
    }
  }
}

dependencies {
  implementation "software.sava:ravina-core:$VERSION"
  implementation "software.sava:ravina-solana:$VERSION"
}
```

### Requirements

- The latest generally available JDK. This project will continue to move to the latest and will not maintain
  versions released against previous JDK's.

### Contribution

Unit tests are needed and welcomed. Otherwise, please open
a [discussion](https://github.com/sava-software/sava/discussions), issue, or send an email before working on a pull
request.
