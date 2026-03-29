package software.sava.services.core.config;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Properties;

public abstract class PropertiesParser {

  public static String propertyPrefix(final String prefix) {
    return prefix == null || prefix.isEmpty() ? "" : prefix.endsWith(".") ? prefix : prefix + ".";
  }

  public static String getProperty(final Properties properties, final String prefix, final String key) {
    final var value = properties.getProperty(prefix + key);
    return value == null || value.isBlank() ? null : value.strip();
  }

  protected static OptionalInt parseInt(final Properties properties, final String prefix, final String key) {
    final var value = getProperty(properties, prefix, key);
    return value == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(value));
  }

  protected static OptionalLong parseLong(final Properties properties, final String prefix, final String key) {
    final var value = getProperty(properties, prefix, key);
    return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
  }

  protected static OptionalDouble parseDouble(final Properties properties, final String prefix, final String key) {
    final var value = getProperty(properties, prefix, key);
    return value == null ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(value));
  }

  protected static boolean parseBoolean(final Properties properties, final String prefix, final String key, final boolean defaultValue) {
    final var value = getProperty(properties, prefix, key);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }

  protected static Duration parseDuration(final Properties properties, final String prefix, final String key) {
    final var value = getProperty(properties, prefix, key);
    return ServiceConfigUtil.parseDuration(value);
  }
}
