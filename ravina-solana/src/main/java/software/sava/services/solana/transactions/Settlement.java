package software.sava.services.solana.transactions;

import software.sava.rpc.json.http.request.Commitment;

import static software.sava.rpc.json.http.request.Commitment.*;

/// When ravina treats a transaction as settled, and the commitment it reads
/// settled chain state at.
///
/// `CONFIRMED` and `FINALIZED` are one settled level here. Under Alpenglow
/// they mark one event: the same finalization certificate moves both (an RPC
/// node may publish `confirmed` a moment before `finalized`), and no status
/// sits between `processed` and `finalized`. Before activation, ravina
/// accepts optimistic confirmation as final. That is a policy, not a
/// protocol guarantee: a confirmed block can still be dropped if about 4.7%
/// of stake breaks its lockouts, which has never been reported on mainnet,
/// and one RPC node's view of `confirmed` can be wrong. The policy spares
/// every caller the 32 slots TowerBFT takes to root a block.
final class Settlement {

  /// The commitment ravina reads block heights and block hashes at,
  /// simulates at, and subscribes to signatures at, whenever it needs settled
  /// state. It stays `CONFIRMED` until Alpenglow is active: on TowerBFT a
  /// `FINALIZED` read lags by about 32 slots, which would shorten every block
  /// hash's validity and delay every settlement. Once Alpenglow is active a
  /// `FINALIZED` read costs no extra wait, so move this to `FINALIZED` then,
  /// ahead of the announced retirement of `confirmed`.
  static final Commitment COMMITMENT = CONFIRMED;

  /// Whether an observed status meets an awaited commitment. `PROCESSED` is
  /// met by any status; every other await is met by either settled level,
  /// so a caller awaiting `FINALIZED` is released at `CONFIRMED`. An unknown
  /// status (a level this client cannot parse) meets nothing above
  /// `PROCESSED`.
  static boolean met(final Commitment awaited, final Commitment observed) {
    return awaited == PROCESSED || observed == CONFIRMED || observed == FINALIZED;
  }

  /// The level an await is known to have reached once it is released, for
  /// logs: `PROCESSED` for a `PROCESSED` await, otherwise the settled level
  /// ravina reads at, so a `FINALIZED` await released at confirmation is not
  /// reported as finalized.
  static Commitment reached(final Commitment awaited) {
    return awaited == PROCESSED ? PROCESSED : COMMITMENT;
  }

  private Settlement() {
  }
}
