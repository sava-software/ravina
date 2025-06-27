package software.sava.services.jetty.handlers;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

public abstract class BaseJettyHandler extends Handler.Abstract implements JettyHandler {

  protected final HttpField allowMethod;

  protected BaseJettyHandler(final InvocationType invocationType, final HttpField allowMethod) {
    super(invocationType);
    this.allowMethod = allowMethod;
  }

  protected void setResponseHeaders(final Response response) {
    final var responseHeaders = response.getHeaders();
    responseHeaders.put(allowMethod);
  }

  @Override
  public boolean handle(final Request request, final Response response, final Callback callback) {
    setResponseHeaders(response);
    return false;
  }

  @Override
  public boolean handlePreFlight(final HttpFields.Mutable responseHeaders, final Callback callback) {
    responseHeaders.put(allowMethod);
    callback.succeeded();
    return true;
  }
}
