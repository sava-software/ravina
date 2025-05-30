# KMS

KMS clients for the purpose of isolating ed25519 private key signing.

## Documentation

User documentation lives at [sava.software](https://sava.software/).

* [Dependency Configuration](https://sava.software/quickstart)
* [KMS](https://sava.software/libraries/kms)

## Contribution

Unit tests are needed and welcomed. Otherwise, please open a discussion, issue, or send an email before working on a
pull request.

## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

Create a `gradle.properties` file in the sava project directory root or under `$HOME/.gradle/`.

### gradle.properties

```properties
gpr.user=GITHUB_USERNAME
gpr.token=GITHUB_TOKEN
```

```shell
./gradlew check
```
