module software.sava.ravina_core {
  requires systems.comodal.json_iterator;

  requires java.net.http;

  exports software.sava.services.core.config;

  exports software.sava.services.core.exceptions;

  exports software.sava.services.core.remote.call;
  exports software.sava.services.core.remote.load_balance;

  exports software.sava.services.core.request_capacity;
  exports software.sava.services.core.request_capacity.context;
  exports software.sava.services.core.request_capacity.trackers;
  exports software.sava.services.core;

  uses software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;

  provides software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory with
      software.sava.services.core.request_capacity.trackers.HttpErrorTrackerFactory;
}
