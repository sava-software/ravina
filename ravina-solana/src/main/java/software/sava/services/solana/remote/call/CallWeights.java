package software.sava.services.solana.remote.call;

import software.sava.services.core.config.PropertiesParser;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.Properties;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record CallWeights(int getProgramAccounts,
                          int getTransaction,
                          int sendTransaction) {

  public static CallWeights parse(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public static CallWeights parseConfig(final Properties properties) {
    return parseConfig(null, properties);
  }

  public static CallWeights parseConfig(final String prefix, final Properties properties) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.create();
  }

  public static CallWeights createDefault() {
    return new CallWeights(2, 5, 10);
  }

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

    private int getProgramAccounts = 2;
    private int getTransaction = 5;
    private int sendTransaction = 10;

    private Parser() {
    }

    private CallWeights create() {
      return new CallWeights(
          getProgramAccounts,
          getTransaction,
          sendTransaction
      );
    }

    private void parseProperties(final String prefix, final Properties properties) {
      final var _prefix = propertyPrefix(prefix);
      parseInt(properties, _prefix, "getProgramAccounts").ifPresent(v -> getProgramAccounts = v);
      parseInt(properties, _prefix, "getTransaction").ifPresent(v -> getTransaction = v);
      parseInt(properties, _prefix, "sendTransaction").ifPresent(v -> sendTransaction = v);
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
