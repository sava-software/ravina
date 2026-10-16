package software.sava.services.core.config;

import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.SequencedCollection;

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
    checkIfFileExists(serviceConfigFile, moduleNameConfigProperty);
    return serviceConfigFile.toAbsolutePath();
  }

  private static void checkIfFileExists(final Path serviceConfigFile, final String moduleNameConfigProperty) {
    if (Files.notExists(serviceConfigFile)) {
      throw new IllegalStateException(String.format("""
          Config file %s does not exist, provide a service configuration file via the System Property [%s], or via the default location [config.json]
          """, serviceConfigFile.toAbsolutePath(), moduleNameConfigProperty
      ));
    }
  }

  public static List<Path> configFilePaths(final Class<?> configClassFile) {
    final var module = configClassFile.getModule();
    final var moduleNameConfigProperty = (module == null
        ? configClassFile.getName()
        : module.getName()) + ".config";
    final var propertyValue = System.getProperty(moduleNameConfigProperty);
    if (propertyValue == null || propertyValue.isBlank()) {
      final var serviceConfigFile = Path.of("config.json");
      checkIfFileExists(serviceConfigFile, moduleNameConfigProperty);
      return List.of(serviceConfigFile);
    } else {
      return Arrays.stream(propertyValue.split(",")).<Path>mapMulti((filePath, downstream) -> {
        if (!filePath.isBlank()) {
          final var serviceConfigFile = Path.of(filePath.strip());
          checkIfFileExists(serviceConfigFile, moduleNameConfigProperty);
          downstream.accept(serviceConfigFile);
        }
      }).toList();
    }
  }

  public static <T> T loadConfig(final Path serviceConfigFile, final Parser<T> parser) {
    try {
      final var ji = JsonIterator.parse(Files.readAllBytes(serviceConfigFile));
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

  public static Properties joinPropertyFiles(final SequencedCollection<String> propertyFiles) {
    return joinPropertyFilePaths(propertyFiles.stream().map(Path::of).toList());
  }

  public static Properties joinPropertyFilePaths(final SequencedCollection<Path> propertyFiles) {
    final var mergedProperties = new Properties();
    for (final var file : propertyFiles) {
      try (final var reader = Files.newBufferedReader(file)) {
        mergedProperties.load(reader);
      } catch (final IOException e) {
        throw new UncheckedIOException("Failed to read properties file: " + file, e);
      }
    }
    return mergedProperties;
  }

  public static Duration parseDuration(final String duration) {
    if (duration == null || duration.isBlank()) {
      return null;
    }
    final var stripped = duration.strip();
    return stripped.startsWith("PT")
        ? Duration.parse(stripped)
        : Duration.parse("PT" + stripped);
  }

  public static Duration parseDuration(final JsonIterator ji) {
    return parseDuration(ji.readString());
  }

  /// The endpoint as it may appear in a log record, an exception message or a `toString()`:
  /// scheme, host and port, with `/(redacted)` in place of a path, query, user info or fragment
  /// left out. Hosted RPC providers and webhooks carry their credential in the URL (a Slack
  /// incoming webhook in its path, an RPC API key in the path or the query), so the full URI is
  /// a credential and only this shape leaves the process. Without a scheme and a parsed host it
  /// renders as `(unparsed endpoint)`, never as the raw authority, which can hold user info;
  /// `null` renders as `null`, as string concatenation would.
  public static String redactEndpoint(final URI endpoint) {
    if (endpoint == null) {
      return "null";
    }
    final var scheme = endpoint.getScheme();
    final var host = endpoint.getHost();
    if (scheme == null || host == null) {
      return "(unparsed endpoint)";
    }
    // a URI with a host is hierarchical, so its raw path is never null
    final var path = endpoint.getRawPath();
    final boolean hidden = !(path.isEmpty() || path.equals("/"))
        || endpoint.getRawQuery() != null
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawFragment() != null;
    final int port = endpoint.getPort();
    return scheme + "://" + host + (port < 0 ? "" : ":" + port) + (hidden ? "/(redacted)" : "");
  }

  /// Refuses an endpoint the URI parser gave no host, such as `https:///v2/KEY` from an empty
  /// template variable or a host name with an underscore. The JDK's HTTP and websocket clients
  /// refuse one on every request, with a message that repeats the whole URI, key included, into
  /// whatever logs the failure; this refusal names the endpoint only redacted, with `what` saying
  /// which endpoint it is. `null` passes, for a config whose endpoint is optional.
  public static URI requireHost(final String what, final URI endpoint) {
    if (endpoint != null && endpoint.getHost() == null) {
      throw new IllegalArgumentException(what + " must be a URL with a host, not " + redactEndpoint(endpoint));
    }
    return endpoint;
  }

  /// [#redactEndpoint(URI)] for an endpoint held as text: a string the URI parser refuses renders
  /// as `(unparsed endpoint)` rather than as itself. It is parsed as [#parseEndpoint] does, so no
  /// exception quoting it is built on the way.
  public static String redactEndpoint(final String endpoint) {
    if (endpoint == null) {
      return "null";
    }
    try {
      return redactEndpoint(new URI(endpoint));
    } catch (final URISyntaxException malformed) {
      return "(unparsed endpoint)";
    }
  }

  /// Parses a configured endpoint, as [URI#create] does, without quoting it when it is refused.
  /// [URI#create] wraps the parser's exception in one whose message repeats the whole string, a
  /// hosted provider's key included, and keeps the parser's exception, which also repeats it, as
  /// the cause; Flight Recorder's `jdk.JavaExceptionThrow` event records such a message as the
  /// exception is constructed, so catching it afterwards is too late. The refusal here names the
  /// endpoint by `what` and gives the parser's reason and index (-1 when the JVM withholds it,
  /// see `jdk.includeInExceptions`), with no cause. The parser's exception records only its
  /// reason: the string is kept apart, and joined only by its `getMessage()`, which is never
  /// called here. A `null` endpoint throws [NullPointerException], as [URI#create] does.
  public static URI parseEndpoint(final String what, final String endpoint) {
    try {
      return new URI(endpoint);
    } catch (final URISyntaxException malformed) {
      throw new IllegalArgumentException(
          what + " is not a valid URI: " + malformed.getReason() + " at index " + malformed.getIndex()
      );
    }
  }

  private ServiceConfigUtil() {
  }
}
