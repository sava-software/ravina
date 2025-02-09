package software.sava.services.core.remote.load_balance;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.net.URI;
import java.net.http.HttpResponse;

public interface BalanceItemFactory<T> {

  BalancedItem<T> createItem(final URI endpoint,
                             final ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> capacityMonitor,
                             final Backoff backoff);
}
