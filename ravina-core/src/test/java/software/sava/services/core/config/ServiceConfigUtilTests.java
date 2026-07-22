package software.sava.services.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.comodal.jsoniter.JsonIterator;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

final class ServiceConfigUtilTests {

  @TempDir
  private Path tempDir;

  private static String configPropertyKey() {
    final var module = ServiceConfigUtilTests.class.getModule();
    return (module == null
        ? ServiceConfigUtilTests.class.getName()
        : module.getName()) + ".config";
  }

  private static final class HostParser implements Parser<String> {

    private String host;

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("host", buf, offset, len)) {
        host = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    }

    @Override
    public String createConfig() {
      return host;
    }
  }

  @Test
  void testConfigFilePathFromSystemProperty() throws Exception {
    final var configFile = tempDir.resolve("service-config.json");
    Files.writeString(configFile, "{}");
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    System.setProperty(key, configFile.toString());
    try {
      final var path = ServiceConfigUtil.configFilePath(ServiceConfigUtilTests.class);
      assertNotNull(path);
      assertEquals(configFile.toAbsolutePath(), path);
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testConfigFilePathMissingFileThrows() {
    final var missing = tempDir.resolve("does-not-exist.json");
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    System.setProperty(key, missing.toString());
    try {
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> ServiceConfigUtil.configFilePath(ServiceConfigUtilTests.class)
      );
      assertTrue(exception.getMessage().contains(key));
      assertTrue(exception.getMessage().contains(missing.toAbsolutePath().toString()));
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testConfigFilePathDefaultsToConfigJson() throws Exception {
    final var defaultPath = Path.of("config.json");
    final boolean created;
    if (Files.notExists(defaultPath)) {
      Files.writeString(defaultPath, "{}");
      created = true;
    } else {
      created = false;
    }
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    try {
      // Unset property falls back to config.json in the working directory.
      System.clearProperty(key);
      var path = ServiceConfigUtil.configFilePath(ServiceConfigUtilTests.class);
      assertEquals(defaultPath.toAbsolutePath(), path);

      // A blank property value must also fall back to config.json.
      System.setProperty(key, "   ");
      path = ServiceConfigUtil.configFilePath(ServiceConfigUtilTests.class);
      assertEquals(defaultPath.toAbsolutePath(), path);

      // Same fallback for the list variant.
      System.clearProperty(key);
      var paths = ServiceConfigUtil.configFilePaths(ServiceConfigUtilTests.class);
      assertEquals(List.of(defaultPath), paths);

      // A blank property value must also fall back for the list variant.
      System.setProperty(key, "   ");
      paths = ServiceConfigUtil.configFilePaths(ServiceConfigUtilTests.class);
      assertEquals(List.of(defaultPath), paths);
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
      if (created) {
        Files.deleteIfExists(defaultPath);
      }
    }
  }

  @Test
  void testConfigFilePathsDefaultMissingThrows() {
    final var defaultPath = Path.of("config.json");
    if (Files.exists(defaultPath)) {
      // Cannot exercise the missing-default-file branch if a config.json is present.
      return;
    }
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    try {
      System.clearProperty(key);
      var exception = assertThrows(
          IllegalStateException.class,
          () -> ServiceConfigUtil.configFilePaths(ServiceConfigUtilTests.class)
      );
      assertTrue(exception.getMessage().contains("config.json"));
      exception = assertThrows(
          IllegalStateException.class,
          () -> ServiceConfigUtil.configFilePath(ServiceConfigUtilTests.class)
      );
      assertTrue(exception.getMessage().contains("config.json"));
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testConfigFilePathsFromSystemProperty() throws Exception {
    final var fileA = tempDir.resolve("a.json");
    final var fileB = tempDir.resolve("b.json");
    Files.writeString(fileA, "{}");
    Files.writeString(fileB, "{}");
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    // Blank segments between commas must be skipped.
    System.setProperty(key, fileA + ", ," + fileB);
    try {
      final var paths = ServiceConfigUtil.configFilePaths(ServiceConfigUtilTests.class);
      assertEquals(List.of(fileA, fileB), paths);
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testMissingFileMessageShowsTheAbsolutePath() {
    // A relative property value only renders absolutely in the error message
    // if toAbsolutePath was applied; an absolute input would hide its removal.
    final var relative = Path.of("service-config-util-tests-missing.json");
    assertTrue(Files.notExists(relative));
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    System.setProperty(key, relative.toString());
    try {
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> ServiceConfigUtil.configFilePath(ServiceConfigUtilTests.class)
      );
      assertTrue(
          exception.getMessage().contains(relative.toAbsolutePath().toString()),
          exception.getMessage()
      );
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testConfigFilePathsStripWhitespaceAroundEntries() throws Exception {
    final var fileA = tempDir.resolve("a.json");
    final var fileB = tempDir.resolve("b.json");
    Files.writeString(fileA, "{}");
    Files.writeString(fileB, "{}");
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    // The space before the second path must be stripped before the existence
    // check, or resolution fails on a path that names no file.
    System.setProperty(key, fileA + ", " + fileB);
    try {
      final var paths = ServiceConfigUtil.configFilePaths(ServiceConfigUtilTests.class);
      assertEquals(List.of(fileA, fileB), paths);
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testConfigFilePathsMissingFileThrows() {
    final var missing = tempDir.resolve("nope.json");
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    System.setProperty(key, missing.toString());
    try {
      final var exception = assertThrows(
          IllegalStateException.class,
          () -> ServiceConfigUtil.configFilePaths(ServiceConfigUtilTests.class)
      );
      assertTrue(exception.getMessage().contains(key));
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testLoadConfigFromFile() throws Exception {
    final var configFile = tempDir.resolve("load.json");
    // Surrounding fields ensure the parser both skips unknown fields and keeps iterating.
    Files.writeString(configFile, """
        {"other": {"nested": 1}, "host": "example.com", "trailing": "ignored"}""");
    final var host = ServiceConfigUtil.loadConfig(configFile, new HostParser());
    assertEquals("example.com", host);
  }

  @Test
  void testLoadConfigMissingFileThrows() {
    final var missing = tempDir.resolve("missing.json");
    assertThrows(
        UncheckedIOException.class,
        () -> ServiceConfigUtil.loadConfig(missing, new HostParser())
    );
  }

  @Test
  void testLoadConfigFromClassProperty() throws Exception {
    final var configFile = tempDir.resolve("class-config.json");
    Files.writeString(configFile, """
        {"host": "from-property.example.com"}""");
    final var key = configPropertyKey();
    final var previous = System.getProperty(key);
    System.setProperty(key, configFile.toString());
    try {
      final var host = ServiceConfigUtil.loadConfig(ServiceConfigUtilTests.class, new HostParser());
      assertEquals("from-property.example.com", host);
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void testJoinPropertyFilePaths() throws Exception {
    final var fileA = tempDir.resolve("a.properties");
    final var fileB = tempDir.resolve("b.properties");
    Files.writeString(fileA, """
        onlyA=1
        shared=fromA
        """);
    Files.writeString(fileB, """
        onlyB=2
        shared=fromB
        """);
    final var merged = ServiceConfigUtil.joinPropertyFilePaths(List.of(fileA, fileB));
    assertNotNull(merged);
    assertEquals(3, merged.size());
    assertEquals("1", merged.getProperty("onlyA"));
    assertEquals("2", merged.getProperty("onlyB"));
    // Later files win on conflicts.
    assertEquals("fromB", merged.getProperty("shared"));
  }

  @Test
  void testJoinPropertyFiles() throws Exception {
    final var fileA = tempDir.resolve("a.properties");
    final var fileB = tempDir.resolve("b.properties");
    Files.writeString(fileA, "k=v\n");
    Files.writeString(fileB, "k2=v2\n");
    final var merged = ServiceConfigUtil.joinPropertyFiles(List.of(fileA.toString(), fileB.toString()));
    assertNotNull(merged);
    assertEquals(2, merged.size());
    assertEquals("v", merged.getProperty("k"));
    assertEquals("v2", merged.getProperty("k2"));
  }

  @Test
  void testJoinPropertyFilesMissingFileThrows() {
    final var missing = tempDir.resolve("missing.properties");
    final var exception = assertThrows(
        UncheckedIOException.class,
        () -> ServiceConfigUtil.joinPropertyFilePaths(List.of(missing))
    );
    assertTrue(exception.getMessage().contains(missing.toString()));
  }

  @Test
  void testParseDurationNullAndBlank() {
    assertNull(ServiceConfigUtil.parseDuration((String) null));
    assertNull(ServiceConfigUtil.parseDuration(""));
    assertNull(ServiceConfigUtil.parseDuration("   "));
  }

  @Test
  void testParseDurationIsoAndBareForms() {
    assertEquals(Duration.ofSeconds(13), ServiceConfigUtil.parseDuration("PT13S"));
    assertEquals(Duration.ofSeconds(13), ServiceConfigUtil.parseDuration("13S"));
    assertEquals(Duration.ofSeconds(13), ServiceConfigUtil.parseDuration("  13S  "));
    assertEquals(Duration.ofMinutes(2), ServiceConfigUtil.parseDuration("2M"));
    assertEquals(Duration.ofMillis(500), ServiceConfigUtil.parseDuration("PT0.5S"));
  }

  @Test
  void testParseDurationFromJson() {
    assertEquals(Duration.ofSeconds(5), ServiceConfigUtil.parseDuration(JsonIterator.parse("\"PT5S\"")));
    assertEquals(Duration.ofSeconds(7), ServiceConfigUtil.parseDuration(JsonIterator.parse("\"7S\"")));
    assertNull(ServiceConfigUtil.parseDuration(JsonIterator.parse("\"\"")));
  }
}
