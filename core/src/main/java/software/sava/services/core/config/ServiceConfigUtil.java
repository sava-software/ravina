package software.sava.services.core.config;

import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class ServiceConfigUtil {

  public static Path configFilePath(final Class<?> configClassFile) {
    final var module = configClassFile.getModule();
    final var moduleNameConfigProperty = (module == null
        ? configClassFile.getName()
        : module.getName()) + ".config";
    final var propertyValue = System.getProperty(moduleNameConfigProperty);
    final Path serviceConfigFile;
    if (propertyValue == null || propertyValue.isBlank()) {
      serviceConfigFile = Path.of("config.json");
    } else {
      serviceConfigFile = Path.of(propertyValue);
    }
    if (!Files.exists(serviceConfigFile)) {
      throw new IllegalStateException(String.format("""
          Config file %s does not exist, provide a service configuration file via the System Property [%s], or via the default location [config.json]
          """, serviceConfigFile.toAbsolutePath(), moduleNameConfigProperty
      ));
    }
    return serviceConfigFile.toAbsolutePath();
  }

  public static <T> T loadConfig(final Path serviceConfigFile, final Parser<T> parser) {
    try (final var ji = JsonIterator.parse(Files.readAllBytes(serviceConfigFile))) {
      ji.testObject(parser);
      return parser.createConfig();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static <T> T loadConfig(final Class<?> configClassFile, final Parser<T> parser) {
    final var serviceConfigFile = ServiceConfigUtil.configFilePath(configClassFile);
    return loadConfig(serviceConfigFile, parser);
  }

  public static Duration parseDuration(final JsonIterator ji) {
    final var duration = ji.readString();
    if (duration == null || duration.isBlank()) {
      return null;
    }
    return duration.startsWith("PT")
        ? Duration.parse(duration)
        : Duration.parse("PT" + duration);
  }

  private ServiceConfigUtil() {
  }
}
