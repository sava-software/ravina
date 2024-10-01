package software.sava.services.solana.remote.call;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record CallWeights(int getProgramAccounts) {

  public static CallWeights parse(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private int getProgramAccounts;

    private Builder() {
    }

    private CallWeights create() {
      return new CallWeights(
          Math.max(1, getProgramAccounts)
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("getProgramAccounts", buf, offset, len)) {
        getProgramAccounts = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
