package software.sava.services.solana.transactions;

/// Thrown by `InstructionService.processInstructions`, and the batch service built on it, in
/// place of a rebuild, when no send of an attempt was accepted (answered with the signature)
/// and the attempt's status then settled as not landed. A rebuild re-simulates, re-prices and
/// takes a new block hash, and a refusal tied to the key, the provider or its rate would
/// answer it the same way, one block hash window at a time, so the call ends here whatever
/// `maxRetriesAfterExpired` allows, and the caller owns the retry.
///
/// With [#expiryVerified()] true the attempt's block hash expired and a history-searching
/// status read behind the settle buffer still found nothing, and every earlier attempt of the
/// call passed the same gate, so nothing the call published can land, barring a status
/// backend that lags the height backend by that buffer or more. A retry is then safe, though
/// not necessarily useful. With it false the call ran with `verifyExpired` false and stopped
/// at the first missing status, so this attempt or an earlier one of the call may still land:
/// rebuild only instructions that are idempotent or deduplicated on chain.
///
/// The cause is the last failed send's failure, which separates a refusal to fix (a 401: stop
/// and fix the key) from one to pace (a 429: back off), with the first failure attached as
/// suppressed when it is another. A `null` cause means every send went unanswered or was
/// answered with another signature. The exception does not claim that the provider refused:
/// a send that timed out was not accepted either.
public final class SendNotAcceptedException extends RuntimeException {

  private final String sig;
  private final int attempted;
  private final boolean expiryVerified;

  private SendNotAcceptedException(final String message,
                                   final Throwable cause,
                                   final String sig,
                                   final int attempted,
                                   final boolean expiryVerified) {
    super(message, cause);
    this.sig = sig;
    this.attempted = attempted;
    this.expiryVerified = expiryVerified;
  }

  static SendNotAcceptedException of(final SendLedger.Reading reading,
                                     final String sig,
                                     final String formattedSig,
                                     final boolean expiryVerified) {
    final int attempted = reading.attempted();
    final var exception = new SendNotAcceptedException(
        String.format(
            "No send of %s was accepted (%d attempted); %s.",
            formattedSig,
            attempted,
            expiryVerified
                ? "its block hash expired with nothing of this call landed,"
                + " so retrying cannot execute it twice"
                : "no status was found and no expiry of this call was verified,"
                + " so this attempt or an earlier one may still land"
        ),
        reading.lastFailure(),
        sig,
        attempted,
        expiryVerified
    );
    final var first = reading.firstFailure();
    // An identity test: one failure recorded once is the cause alone, never reported both as
    // the cause and as suppressed.
    if (first != reading.lastFailure()) {
      exception.addSuppressed(first);
    }
    return exception;
  }

  /// The signature of the attempt that was not accepted, in base58.
  public String sig() {
    return sig;
  }

  /// How many sends of that attempt were made: the first send, a failover, and each resend.
  public int attempted() {
    return attempted;
  }

  /// Whether the call verified that its block hash expired with nothing landed, so that
  /// nothing it published can still land; see the class description.
  public boolean expiryVerified() {
    return expiryVerified;
  }
}
