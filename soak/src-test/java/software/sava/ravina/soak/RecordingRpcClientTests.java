package software.sava.ravina.soak;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.request.Commitment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The report's poll batch size is the number of signatures in a `getSigStatusList` call. The
/// signatures come first in the overloads read at the server's default commitment and second in
/// the ones that name a commitment, which the expiration monitor calls (regression: the count
/// read the first argument only, so a commitment-first call would have been recorded as a batch
/// of none).
final class RecordingRpcClientTests {

  @Test
  void theSignaturesAreCountedWhereverTheOverloadPutsThem() {
    final var signatures = List.of("first", "second", "third");

    assertEquals(3, RecordingRpcClient.signatureCount(new Object[]{signatures, true}));
    assertEquals(3, RecordingRpcClient.signatureCount(new Object[]{Commitment.FINALIZED, signatures, true}));
    assertEquals(3, RecordingRpcClient.signatureCount(new Object[]{Commitment.FINALIZED, signatures, true, null}));
  }

  @Test
  void aCallWithoutSignaturesCountsNone() {
    assertEquals(0, RecordingRpcClient.signatureCount(null));
    assertEquals(0, RecordingRpcClient.signatureCount(new Object[]{Commitment.FINALIZED, true}));
  }
}
