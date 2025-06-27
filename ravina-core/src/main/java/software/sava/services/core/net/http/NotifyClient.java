package software.sava.services.core.net.http;

import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@FunctionalInterface
public interface NotifyClient {

  static NotifyClient createClient(final ExecutorService executorService,
                                   final List<ClientCaller<WebHookClient>> webHookClients,
                                   final CallContext callContext) {
    if (webHookClients.isEmpty()) {
      final var noop = List.<CompletableFuture<String>>of();
      return _ -> noop;
    } else {
      return new NotifyClientImpl(executorService, webHookClients, callContext);
    }
  }

  List<CompletableFuture<String>> postMsg(final String msg);
}
