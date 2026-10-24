package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;

import static software.sava.rpc.json.http.request.Commitment.*;

/// When ravina treats a transaction as settled, and the commitment it reads
/// settled chain state at.
///
/// `CONFIRMED` and `FINALIZED` are one settled level here. On an Alpenglow
/// node, which every public cluster has been since October 2026, they mark one
/// event: the node roots a block covered by a finalization certificate and
/// publishes both levels for it, through two channels, so one may lead the
/// other by a moment but nothing sits between `processed` and `finalized`.
/// Where `finalized` trails `confirmed` it lags by about 32 slots: on an agave
/// test validator running TowerBFT (before 4.5.0-alpha.2, started without
/// `--alpenglow`), where `confirmed` is optimistic confirmation, and on a
/// Surfpool surfnet, which runs no consensus and reports `finalized` a fixed
/// 30 slots behind `confirmed`. There this policy still accepts a `CONFIRMED`
/// status as final while its settled reads moved to `finalized`, so a
/// transaction released at confirmation is not yet in the bank a finalized
/// simulation reads for those slots. Logs report the level actually observed,
/// so a settled await released by a `CONFIRMED` status on such an environment
/// is never reported as finalized.
final class Settlement {

  /// The commitment ravina reads block heights and block hashes at, simulates
  /// at, and subscribes to signatures at, whenever it needs settled state.
  /// `FINALIZED` since 2026-10-24: on an Alpenglow node it costs no extra wait
  /// over `confirmed`, which the Solana Foundation says is removed in a later
  /// release. Where `finalized` trails `confirmed`, every settled read and
  /// `FINALIZED` subscription lags confirmation by about 32 slots, so a settled
  /// websocket await there runs out `TxMonitorConfig.webSocketConfirmationTimeout`
  /// (unless that is set longer than the lag) and polling settles it on the
  /// `CONFIRMED` status; that is the accepted cost, and the soak starts its
  /// validator with `--alpenglow` for that reason.
  static final Commitment COMMITMENT = FINALIZED;

  /// Whether an observed status meets an awaited commitment. `PROCESSED` is
  /// met by any status; every other await is met by either settled level,
  /// so a caller awaiting `FINALIZED` is released by a `CONFIRMED` status
  /// too. An unknown status (a level this client cannot parse) meets nothing
  /// above `PROCESSED`.
  static boolean met(final Commitment awaited, final Commitment observed) {
    return awaited == PROCESSED || observed == CONFIRMED || observed == FINALIZED;
  }

  /// The level an await is waiting to reach, for logs written before it is
  /// met: `PROCESSED` for a `PROCESSED` await, otherwise the settled level
  /// ravina subscribes at and reads at.
  static Commitment awaiting(final Commitment awaited) {
    return awaited == PROCESSED ? PROCESSED : COMMITMENT;
  }

  /// The level a released await is known to have reached, for logs:
  /// `PROCESSED` for a `PROCESSED` await, otherwise the status actually
  /// observed, so a `FINALIZED` await released by a `CONFIRMED` status is
  /// reported as confirmed, not finalized.
  static Commitment reached(final Commitment awaited, final Commitment observed) {
    return awaited == PROCESSED ? PROCESSED : observed;
  }

  private Settlement() {
  }
}
