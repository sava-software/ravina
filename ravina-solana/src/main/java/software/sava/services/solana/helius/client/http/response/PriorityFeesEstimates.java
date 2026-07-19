package software.sava.services.solana.helius.client.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record PriorityFeesEstimates(double min,
                                    double low,
                                    double medium,
                                    double high,
                                    double veryHigh,
                                    double unsafeMax) {

  public static PriorityFeesEstimates parseLevels(final JsonIterator ji) {
    return ji.skipUntil("priorityFeeLevels").testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("min", buf, offset, len)) {
      builder.min = ji.readDouble();
    } else if (fieldEquals("low", buf, offset, len)) {
      builder.low = ji.readDouble();
    } else if (fieldEquals("medium", buf, offset, len)) {
      builder.medium = ji.readDouble();
    } else if (fieldEquals("high", buf, offset, len)) {
      builder.high = ji.readDouble();
    } else if (fieldEquals("veryHigh", buf, offset, len)) {
      builder.veryHigh = ji.readDouble();
    } else if (fieldEquals("unsafeMax", buf, offset, len)) {
      builder.unsafeMax = ji.readDouble();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private double min;
    private double low;
    private double medium;
    private double high;
    private double veryHigh;
    private double unsafeMax;

    private Builder() {
    }

    private PriorityFeesEstimates create() {
      return new PriorityFeesEstimates(min, low, medium, high, veryHigh, unsafeMax);
    }
  }
}
