package software.sava.services.jetty.handlers;

import java.util.ArrayList;

public class HandlerUtil {

  private static String parseParam(final String query,
                                   final int index,
                                   final String param) {
    final int from = index + param.length();
    final int to = query.indexOf('&', from + 1);
    return to < 0
        ? query.substring(from)
        : query.substring(from, to);
  }

  public static boolean parseParam(final String query, final String param, final boolean defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      int index = query.indexOf(param);
      return index < 0 ? defaultValue : Boolean.parseBoolean(parseParam(query, index, param));
    }
  }

  public static boolean parseBoolParam(final String query, final String param) {
    return parseParam(query, param, false);
  }

  public static int parseParam(final String query, final String param, final int defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      int index = query.indexOf(param);
      return index < 0 ? defaultValue : Integer.parseInt(parseParam(query, index, param));
    }
  }

  public static int parseIntParam(final String query, final String param) {
    return parseParam(query, param, 0);
  }

  public static String parseParam(final String query, final String param, final String defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      int index = query.indexOf(param);
      return index < 0 ? defaultValue : parseParam(query, index, param);
    }
  }

  public static String parseParam(final String query, final String param) {
    return parseParam(query, param, null);
  }

  public static int[] parseIntParams(final String query, final String param, final int defaultSize) {
    final var val = parseParam(query, param, null);
    if (val == null || val.isBlank()) {
      return null;
    }
    final var values = new ArrayList<String>(defaultSize);
    for (int from = 0, to; ; ) {
      to = val.indexOf(',', from);
      if (to < 0) {
        values.add(val.substring(from));
        break;
      } else {
        values.add(val.substring(from, to));
        ++to;
        from = to;
      }
    }
    return values.stream().mapToInt(Integer::parseInt).toArray();
  }

  private HandlerUtil() {
  }
}
