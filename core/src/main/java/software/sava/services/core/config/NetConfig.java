package software.sava.services.core.config;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record NetConfig(int port) {

  public static NetConfig parseConfig(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private int port;

    private Builder() {
    }

    private NetConfig create() {
      return new NetConfig(port);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("port", buf, offset, len)) {
        port = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
