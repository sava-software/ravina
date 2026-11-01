package software.sava.services.core.net.http;

import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.context.CallContext;
import systems.comodal.jsoniter.JIUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static java.lang.System.Logger.Level.WARNING;

final class NotifyClientImpl implements NotifyClient {

  private static final System.Logger logger = System.getLogger(NotifyClientImpl.class.getName());

  private final ExecutorService executorService;
  private final List<ClientCaller<WebHookClient>> webHookClients;
  private final CallContext callContext;

  NotifyClientImpl(final ExecutorService executorService,
                   final List<ClientCaller<WebHookClient>> webHookClients,
                   final CallContext callContext) {
    this.executorService = executorService;
    this.webHookClients = webHookClients;
    this.callContext = callContext;
  }

  @Override
  public ArrayList<CompletableFuture<String>> postMsg(final String msg) {
    // The webhook's template puts the message inside a JSON string literal, so every character
    // RFC 8259 requires escaped there is: the quote, the backslash and the controls below U+0020.
    // A message that needs none is forwarded as the same instance.
    final var escaped = JIUtil.escapeJson(msg);

    final var responseFutures = new ArrayList<CompletableFuture<String>>(webHookClients.size());

    for (final var caller : webHookClients) {

      final var responseFuture = caller.createCourteousCall(
          webHookClient -> webHookClient.postMsg(escaped),
          callContext,
          "webHookClient::postMsg"
      ).async(executorService);

      responseFutures.add(responseFuture);

      responseFuture.exceptionally(throwable -> {
        logger.log(WARNING, String.format("""
                Failed to POST event to %s:
                
                %s
                """, ServiceConfigUtil.redactEndpoint(caller.client().endpoint()), msg
            ), throwable
        );
        return null;
      });
    }

    return responseFutures;
  }
}
