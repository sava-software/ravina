package software.sava.services.solana.helius.client.http.request;

import java.util.List;

public final class PriorityFeeRequest {

  public static final int DEFAULT_LOOK_BACK_SLOTS = 150;
  public static final Encoding DEFAULT_TX_ENCODING = Encoding.base64;

  public static String serializeParams(final List<String> accountKeys, final int lookBackSlots) {

    final int numKeys = accountKeys.size();
    final var accountKeysString = numKeys == 1
        ? accountKeys.getFirst()
        : String.join("\",\"", accountKeys);
    return String.format("""
            "accountKeys":["%s"],
            "options":{
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":%d
            }""",
        accountKeysString,
        lookBackSlots
    );
  }

  public static String serializeParams(final List<String> accountKeys) {
    return serializeParams(accountKeys, DEFAULT_LOOK_BACK_SLOTS);
  }

  public static String serializeParams(final String transaction,
                                       final String transactionEncoding,
                                       final int lookBackSlots) {
    return String.format("""
            "transaction":"%s",
            "options":{
              "transactionEncoding":"%s",
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":%d
            }""",
        transaction,
        transactionEncoding,
        lookBackSlots
    );
  }

  public static String serializeParams(final String transaction,
                                       final Encoding transactionEncoding,
                                       final int lookBackSlots) {
    return serializeParams(transaction, transactionEncoding.name(), lookBackSlots);
  }

  public static String serializeParams(final String transaction) {
    return serializeParams(transaction, DEFAULT_TX_ENCODING, DEFAULT_LOOK_BACK_SLOTS);
  }

  public static String serializeParams(final String transaction, final Encoding transactionEncoding) {
    return serializeParams(transaction, transactionEncoding, DEFAULT_LOOK_BACK_SLOTS);
  }

  public static String serializeParams(final String transaction, final int lookBackSlots) {
    return serializeParams(transaction, DEFAULT_TX_ENCODING, lookBackSlots);
  }


  public static String serializeRecommendedParams(final List<String> accountKeys) {

    final int numKeys = accountKeys.size();
    final var accountKeysString = numKeys == 1
        ? accountKeys.getFirst()
        : String.join("\",\"", accountKeys);
    return String.format("""
            "accountKeys":["%s"],
            "options":{
              "recommended":true
            }""",
        accountKeysString
    );
  }

  public static String serializeRecommendedParams(final String transaction, final Encoding transactionEncoding) {
    return String.format("""
            "transaction":"%s",
            "options":{
              "transactionEncoding":"%s",
              "recommended":true
            }""",
        transaction,
        transactionEncoding
    );
  }

  public static String serializeRecommendedParams(final String transaction) {
    return serializeRecommendedParams(transaction, DEFAULT_TX_ENCODING);
  }

  private PriorityFeeRequest() {
  }
}
