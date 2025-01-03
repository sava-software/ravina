package software.sava.services.core.config;

import systems.comodal.jsoniter.FieldBufferPredicate;

public interface Parser<T> extends FieldBufferPredicate {

  T createConfig();
}
