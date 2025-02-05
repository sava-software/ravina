package software.sava.services.core.remote.call;

import software.sava.services.core.request_capacity.CapacityState;

record CallerRecord(CapacityState capacityState, Backoff backoff) implements Caller {

}
