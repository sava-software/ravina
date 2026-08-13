package software.sava.services.solana.helius.client.http.request;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static software.sava.services.solana.helius.client.http.request.PriorityFeeRequest.serializeParams;
import static software.sava.services.solana.helius.client.http.request.PriorityFeeRequest.serializeRecommendedParams;

/// Every method here is pure string assembly, so each overload is pinned to its
/// exact emitted JSON fragment. The fragments are embedded verbatim rather than
/// re-derived from the implementation so that an emptied or re-delegated
/// overload cannot agree with its own expectation.
final class PriorityFeeRequestTests {

  private static final String TX = "AQIDBAU=";

  @Test
  void defaultsAreTheDocumentedHeliusDefaults() {
    assertEquals(1, PriorityFeeRequest.MIN_LOOK_BACK_SLOTS);
    assertEquals(150, PriorityFeeRequest.MAX_LOOK_BACK_SLOTS);
    assertEquals(PriorityFeeRequest.MAX_LOOK_BACK_SLOTS, PriorityFeeRequest.DEFAULT_LOOK_BACK_SLOTS);
    assertEquals(Encoding.base64, PriorityFeeRequest.DEFAULT_TX_ENCODING);
  }

  @Test
  void accountKeyLookbacksEnforceTheHeliusRange() {
    assertDoesNotThrow(() -> serializeParams(List.of("onlyKey"), PriorityFeeRequest.MIN_LOOK_BACK_SLOTS));
    assertDoesNotThrow(() -> serializeParams(List.of("onlyKey"), PriorityFeeRequest.MAX_LOOK_BACK_SLOTS));
    assertThrows(IllegalArgumentException.class, () -> serializeParams(List.of("onlyKey"), 0));
    assertThrows(IllegalArgumentException.class, () -> serializeParams(List.of("onlyKey"), 151));
  }

  @Test
  void transactionLookbacksEnforceTheHeliusRangeThroughEveryExplicitOverload() {
    assertDoesNotThrow(() -> serializeParams(TX, "base64", PriorityFeeRequest.MIN_LOOK_BACK_SLOTS));
    assertDoesNotThrow(() -> serializeParams(TX, Encoding.base64, PriorityFeeRequest.MAX_LOOK_BACK_SLOTS));
    assertDoesNotThrow(() -> serializeParams(TX, PriorityFeeRequest.MIN_LOOK_BACK_SLOTS));

    assertThrows(IllegalArgumentException.class, () -> serializeParams(TX, "base64", 0));
    assertThrows(IllegalArgumentException.class, () -> serializeParams(TX, Encoding.base64, 151));
    assertThrows(IllegalArgumentException.class, () -> serializeParams(TX, 0));
  }

  @Test
  void multipleAccountKeysAreJoinedIntoOneQuotedArray() {
    // The joiner supplies the interior quotes; the format supplies the outer pair.
    assertEquals("""
            "accountKeys":["keyA","keyB","keyC"],
            "options":{
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":42
            }""",
        serializeParams(List.of("keyA", "keyB", "keyC"), 42)
    );
  }

  @Test
  void aSingleAccountKeyTakesTheNoJoinFastPath() {
    assertEquals("""
            "accountKeys":["onlyKey"],
            "options":{
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":7
            }""",
        serializeParams(List.of("onlyKey"), 7)
    );
  }

  @Test
  void accountKeysWithoutALookbackUseTheDefaultLookback() {
    assertEquals("""
            "accountKeys":["keyA","keyB"],
            "options":{
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":150
            }""",
        serializeParams(List.of("keyA", "keyB"))
    );
  }

  @Test
  void aTransactionWithAnExplicitEncodingString() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base58",
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":13
            }""",
        serializeParams(TX, "base58", 13)
    );
  }

  @Test
  void anEncodingEnumIsSerializedByName() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base58",
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":13
            }""",
        serializeParams(TX, Encoding.base58, 13)
    );
  }

  @Test
  void aTransactionAloneUsesBothDefaults() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base64",
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":150
            }""",
        serializeParams(TX)
    );
  }

  @Test
  void aTransactionWithAnEncodingDefaultsOnlyTheLookback() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base58",
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":150
            }""",
        serializeParams(TX, Encoding.base58)
    );
  }

  @Test
  void aTransactionWithALookbackDefaultsOnlyTheEncoding() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base64",
              "includeAllPriorityFeeLevels":true,
              "lookbackSlots":99
            }""",
        serializeParams(TX, 99)
    );
  }

  @Test
  void recommendedAccountKeysDropTheLookbackAndLevelOptions() {
    assertEquals("""
            "accountKeys":["keyA","keyB","keyC"],
            "options":{
              "recommended":true
            }""",
        serializeRecommendedParams(List.of("keyA", "keyB", "keyC"))
    );
  }

  @Test
  void aSingleRecommendedAccountKeyTakesTheNoJoinFastPath() {
    assertEquals("""
            "accountKeys":["onlyKey"],
            "options":{
              "recommended":true
            }""",
        serializeRecommendedParams(List.of("onlyKey"))
    );
  }

  @Test
  void recommendedTransactionParamsCarryTheEncoding() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base58",
              "recommended":true
            }""",
        serializeRecommendedParams(TX, Encoding.base58)
    );
  }

  @Test
  void aRecommendedTransactionAloneUsesTheDefaultEncoding() {
    assertEquals("""
            "transaction":"AQIDBAU=",
            "options":{
              "transactionEncoding":"base64",
              "recommended":true
            }""",
        serializeRecommendedParams(TX)
    );
  }
}
