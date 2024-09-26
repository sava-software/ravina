package software.sava.services.solana.accounts.lookup;

import software.sava.services.core.remote.call.ErrorHandler;
import software.sava.services.core.remote.load_balance.BalancedItem;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;
import software.sava.services.core.request_capacity.UriCapacityConfig;

import java.net.http.HttpResponse;

public interface BalanceItemFactory<T> {

  BalancedItem<T> createItem(final UriCapacityConfig uriCapacityConfig,
                             final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                             final ErrorHandler errorHandler);
}
