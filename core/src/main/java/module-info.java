import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.HttpErrorTrackerFactory;

module software.sava.service_core {
  requires systems.comodal.json_iterator;

  requires org.bouncycastle.provider;

  requires software.sava.rpc;
  requires java.net.http;

  uses ErrorTrackerFactory;

  provides ErrorTrackerFactory with
      HttpErrorTrackerFactory;

  exports software.sava.services.core.exceptions;

  exports software.sava.services.core.remote.call;
  exports software.sava.services.core.remote.load_balance;

  exports software.sava.services.core.request_capacity;
  exports software.sava.services.core.request_capacity.context;
  exports software.sava.services.core.request_capacity.trackers;

}
