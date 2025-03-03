package software.sava.services.core.net.http;

import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.context.CallContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public interface NotifyClient {

  static NotifyClient createClient(final ExecutorService executorService,
                                   final List<ClientCaller<WebHookClient>> webHookClients,
                                   final CallContext callContext) {
    return new NotifyClientImpl(executorService, webHookClients, callContext);
  }

  ArrayList<CompletableFuture<String>> postMsg(final String msg);
}
