module software.sava.ravina_core {
  requires java.net.http;

  requires systems.comodal.json_iterator;

  exports software.sava.services.core;
  exports software.sava.services.core.config;
  exports software.sava.services.core.exceptions;
  exports software.sava.services.core.net.http;
  exports software.sava.services.core.remote.call;
  exports software.sava.services.core.remote.load_balance;
  exports software.sava.services.core.request_capacity;
  exports software.sava.services.core.request_capacity.context;
  exports software.sava.services.core.request_capacity.trackers;

  uses software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;

  provides software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory with
      software.sava.services.core.request_capacity.trackers.HttpErrorTrackerFactory;
}
