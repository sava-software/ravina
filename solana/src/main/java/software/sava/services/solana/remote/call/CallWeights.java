package software.sava.services.solana.remote.call;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record CallWeights(int getProgramAccounts,
                          int getTransaction,
                          int sendTransaction) {

  public static CallWeights parse(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }
  }

  private static final class Builder implements FieldBufferPredicate {

    private int getProgramAccounts = 2;
    private int getTransaction = 5;
    private int sendTransaction = 10;

    private Builder() {
    }

    private CallWeights create() {
      return new CallWeights(
          getProgramAccounts,
          getTransaction,
          sendTransaction
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("getProgramAccounts", buf, offset, len)) {
        getProgramAccounts = ji.readInt();
      } else if (fieldEquals("getTransaction", buf, offset, len)) {
        getTransaction = ji.readInt();
      } else if (fieldEquals("sendTransaction", buf, offset, len)) {
        sendTransaction = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
