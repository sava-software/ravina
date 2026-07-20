package software.sava.services.solana.helius.client.http.response;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class PriorityFeesEstimatesTests {

  private static PriorityFeesEstimates parse(final String json) {
    return PriorityFeesEstimates.parseLevels(JsonIterator.parse(json.getBytes(UTF_8)));
  }

  @Test
  void everyLevelIsParsedIntoItsOwnComponent() {
    // Distinct values per level so a transposed or dropped assignment is visible.
    final var estimates = parse("""
        {"priorityFeeLevels":{\
        "min":1.5,"low":2.5,"medium":3.5,"high":4.5,"veryHigh":5.5,"unsafeMax":6.5}}""");
    assertNotNull(estimates);
    assertEquals(1.5, estimates.min());
    assertEquals(2.5, estimates.low());
    assertEquals(3.5, estimates.medium());
    assertEquals(4.5, estimates.high());
    assertEquals(5.5, estimates.veryHigh());
    assertEquals(6.5, estimates.unsafeMax());
    assertEquals(new PriorityFeesEstimates(1.5, 2.5, 3.5, 4.5, 5.5, 6.5), estimates);
  }

  @Test
  void levelsAreFoundBehindLeadingFieldsAndOutOfOrder() {
    final var estimates = parse("""
        {"ignored":{"min":99.0},"priorityFeeLevels":{\
        "unsafeMax":6,"veryHigh":5,"high":4,"medium":3,"low":2,"min":1}}""");
    assertEquals(new PriorityFeesEstimates(1, 2, 3, 4, 5, 6), estimates);
  }

  @Test
  void unknownLevelsAreSkippedRatherThanFailing() {
    final var estimates = parse("""
        {"priorityFeeLevels":{"min":1,"unknownLevel":{"nested":[1,2,3]},"unsafeMax":6}}""");
    assertEquals(new PriorityFeesEstimates(1, 0, 0, 0, 0, 6), estimates);
  }

  @Test
  void absentLevelsDefaultToZero() {
    final var estimates = parse("""
        {"priorityFeeLevels":{}}""");
    assertNotNull(estimates);
    assertEquals(new PriorityFeesEstimates(0, 0, 0, 0, 0, 0), estimates);
  }
}
