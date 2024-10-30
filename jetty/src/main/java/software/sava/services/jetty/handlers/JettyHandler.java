package software.sava.services.jetty.handlers;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Callback;

public interface JettyHandler extends Handler {

  boolean handlePreFlight(final HttpFields.Mutable responseHeaders, final Callback callback);
}
