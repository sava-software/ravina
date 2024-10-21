package software.sava.services.jetty.handlers;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.util.Map;
import java.util.Set;

import static java.lang.System.Logger.Level.ERROR;
import static software.sava.services.jetty.handlers.BaseJettyHandler.JSON_CONTENT;

public class RootJettyHandler extends Handler.Sequence {

  private static final System.Logger logger = System.getLogger(RootJettyHandler.class.getName());

  private final Map<String, ? extends BaseJettyHandler> handlerMap;
  private final boolean localDev;
  private final Set<String> allowedOrigins;

  public RootJettyHandler(final Map<String, ? extends BaseJettyHandler> handlerMap,
                          final Set<String> allowedOrigins,
                          final boolean localDev) {
    this.handlerMap = handlerMap;
    this.localDev = localDev;
    this.allowedOrigins = allowedOrigins;
  }

  public BaseJettyHandler findHandler(final Request request) {
    return null;
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    final var responseHeaders = response.getHeaders();
    try {
      final var path = request.getHttpURI().getCanonicalPath();
      var handler = handlerMap.get(path);
      if (handler == null) {
        handler = findHandler(request);
        if (handler == null) {
          response.setStatus(404);
          responseHeaders.put(JSON_CONTENT);
          Content.Sink.write(response, true, """
              {
                "msg": "No handler for path."
              }""", callback);
          return true;
        } else {
          return handler.handle(request, response, callback);
        }
      } else {
        final var requestHeaders = request.getHeaders();
        final var origin = requestHeaders.get(HttpHeader.ORIGIN);
        if (origin == null) {
          if (invalidAuth(requestHeaders)) {
            response.setStatus(403);
            responseHeaders.put(JSON_CONTENT);
            Content.Sink.write(response, true, """
                {
                  "msg": "Origin header is required."
                }""", callback);
            return true;
          }
        } else {
          if (!allowedOrigins.contains(origin) && invalidAuth(requestHeaders)) {
            response.setStatus(403);
            responseHeaders.put(JSON_CONTENT);
            Content.Sink.write(response, true, String.format("""
                {
                  "msg": "Invalid Origin: %s"
                }""", origin), callback);
            return true;
          } else {
            responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            // if pre-flight check.
            if (HttpMethod.OPTIONS.is(request.getMethod()) && requestHeaders.contains(HttpHeader.ACCESS_CONTROL_REQUEST_METHOD)) {
              responseHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders.get(HttpHeader.ACCESS_CONTROL_REQUEST_HEADERS));
              return handler.handle(responseHeaders, callback);
            }
          }
        }
        return handler.handle(request, response, callback);
      }
    } catch (final Throwable throwable) {
      logger.log(ERROR, "Failed to process request.", throwable);
      response.setStatus(500);
      callback.failed(throwable);
      return true;
    }
  }

  private boolean invalidAuth(final HttpFields headers) {
    if (localDev) {
      return false;
    }
    // TODO: implement api key validation
    final var apiKey = headers.get("X-API-KEY");
    return !"API_KEY".equals(apiKey);
  }
}
