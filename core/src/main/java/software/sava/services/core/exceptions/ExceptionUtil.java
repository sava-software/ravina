package software.sava.services.core.exceptions;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class ExceptionUtil {

  public static boolean containsIOException(Throwable throwable) {
    if (throwable == null) {
      return false;
    }
    do {
      if (throwable instanceof IOException || throwable instanceof UncheckedIOException) {
        return true;
      }
    } while ((throwable = throwable.getCause()) != null);
    return false;
  }

  public static boolean containsException(Throwable throwable, final Class<?> clas) {
    if (throwable == null) {
      return false;
    }
    do {
      if (clas.isInstance(throwable)) {
        return true;
      }
    } while ((throwable = throwable.getCause()) != null);
    return false;
  }

  public static Throwable getException(Throwable throwable, final Class<?> clas) {
    if (throwable == null) {
      return null;
    }
    do {
      if (clas.isInstance(throwable)) {
        return throwable;
      }
    } while ((throwable = throwable.getCause()) != null);
    return null;
  }

  private ExceptionUtil() {

  }
}
