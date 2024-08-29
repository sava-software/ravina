package software.sava.services.core.request_capacity.context;

record SimpleCallContext(int callWeight, int minCapacity) implements CallContext {

  static final int DEFAULT_CALL_CONTEXT_WEIGHT = 1;
  static final int DEFAULT_MIN_CALL_CONTEXT_CAPACITY = 0;
}
