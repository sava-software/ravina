package software.sava.services.core;

import java.util.logging.Level;
import java.util.logging.Logger;

/// Test-only scope that pins a JDK logger to a level for the duration of a
/// try-with-resources block and restores the previous level on exit.
///
/// Several tests deliberately drive main-source code down an error path that
/// logs the throwable it handled. The stack trace lands in the build output
/// where it is indistinguishable from a real failure. Wrap **only** the call
/// that logs:
///
/// ```
/// try (var ignored = LogSilencer.silenced(HttpErrorTracker.class)) {
///   assertTrue(tracker.test(response, null));
/// }
/// ```
///
/// Silencing a whole test that has other, unrelated assertions would hide the
/// next genuine error too, which is the thing this is meant to make visible.
///
/// The level is always **set**, never inherited: an assertion that branches on
/// the ambient logging configuration passes or fails depending on the JVM's
/// `logging.properties`, and this repository has already paid for that bug once.
///
/// [#close()] declares no checked exception, so a silenced block can still
/// produce a value or propagate a checked exception of its own.
///
/// `java.util.logging` holds its loggers weakly. Retaining the reference here
/// for the lifetime of the scope keeps the level from being collected — and so
/// silently discarded — part way through the block.
public final class LogSilencer implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;

  private LogSilencer(final Logger logger, final Level level) {
    this.logger = logger;
    this.previousLevel = logger.getLevel();
    logger.setLevel(level);
  }

  /// Suppresses everything logged by `loggingClass` until the scope closes.
  public static LogSilencer silenced(final Class<?> loggingClass) {
    return forceLevel(loggingClass, Level.OFF);
  }

  /// Forces `loggingClass` to `level`, in either direction — used to make a
  /// logging branch reachable as well as to mute one.
  public static LogSilencer forceLevel(final Class<?> loggingClass, final Level level) {
    return new LogSilencer(Logger.getLogger(loggingClass.getName()), level);
  }

  @Override
  public void close() {
    logger.setLevel(previousLevel);
  }
}
