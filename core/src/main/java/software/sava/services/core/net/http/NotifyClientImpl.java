package software.sava.services.core.net.http;

import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.context.CallContext;

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

  private static String escape(final String msg) {
    final char[] chars = msg.toCharArray();
    final char[] escaped = new char[chars.length << 1];
    char c;
    int i = 0, j = 0;
    for (; i < chars.length; ++i, ++j) {
      c = chars[i];
      if (c == '"') {
        escaped[j++] = '\\';
        escaped[j] = c;
      } else if (c == '\n') {
        escaped[j++] = '\\';
        escaped[j] = 'n';
      } else {
        escaped[j] = c;
      }
    }
    return i == j ? msg : new String(escaped, 0, j);
  }

  @Override
  public ArrayList<CompletableFuture<String>> postMsg(final String msg) {
    final var escaped = escape(msg);

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
                """, caller.client().endpoint(), msg
            ), throwable
        );
        return null;
      });
    }

    return responseFutures;
  }
}
