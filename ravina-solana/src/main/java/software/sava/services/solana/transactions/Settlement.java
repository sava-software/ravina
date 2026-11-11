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
/// `finalized` trails `confirmed` on development setups only, by about 32
/// slots: on an agave test validator running TowerBFT (before 4.5.0-alpha.2,
/// started without `--alpenglow`), where `confirmed` is optimistic
/// confirmation, and on a Surfpool surfnet, which runs no consensus and reports
/// `finalized` a fixed 30 slots behind `confirmed`. There this policy still
/// accepts a `CONFIRMED` status as final while its settled reads are at
/// `finalized`. On that agave validator a transaction released at confirmation
/// is also absent from the root bank a finalized simulation reads; Surfpool
/// simulates against current state and only delays statuses, block hashes and
/// heights. Logs report the observed level, so a `CONFIRMED` release there is
/// never logged as finalized.
///
/// Asking and answering are separate. Ravina never asks for `confirmed` on
/// its own behalf: a request it makes for settled state names [#COMMITMENT],
/// and only a commitment a caller passes in is sent as given. What comes back is
/// weighed by [#met] whatever was asked, so a `CONFIRMED` status settles a
/// settled await wherever one is reported: by a status poll that named no
/// commitment, on a node where `finalized` trails `confirmed`, and by a server
/// that ignores a status read's commitment (agave before 4.4, Surfpool).
///
/// The commitment monitor's status poll reads each transaction at the level
/// its caller's settings ask of a status ([#polledSettled]). A transaction
/// whose expiry is verified and whose awaits are both settled, which the
/// overloads that take no commitment ask for, is read at [#COMMITMENT] like
/// every other settled read, since 2026-11-11: its status is found once its
/// block is final, and a missing one is never a verdict there, it makes a
/// resend due or hands the transaction to the expiration monitor, whose own
/// read at `finalized` decides "never landed". The price is the gap between a
/// node's processed bank and its finalized one, one slot, about 215 ms, in
/// nearly every sample of mainnet-beta and testnet on 2026-11-11 and one or two
/// on devnet: a transaction that landed inside it reads as missing for one pass
/// and can be resent once, the same bytes, which land at most once. If
/// finality fell behind the blocks being produced the
/// resends would repeat, one each resend delay, until it caught up, since the
/// resend floor is counted from the finalized height.
///
/// The other transactions are read with no commitment named, which a node
/// answers from its processed bank. With `verifyExpired` false the first
/// missing status is the "never landed" verdict the caller rebuilds on; read
/// at `finalized` it would also be the answer for a transaction that landed a
/// slot ago, and the rebuild would execute its instructions a second time. A
/// `PROCESSED` await, on the result or on an error, is released by a status
/// that is not final. Do not move either to [#COMMITMENT] with the rest. A
/// pass that holds both kinds makes both reads, and each transaction is
/// answered from its own.
final class Settlement {

  /// The commitment ravina reads block heights and block hashes at, simulates
  /// at, and subscribes to signatures at, whenever it needs settled state.
  /// `FINALIZED` since 2026-10-24: on an Alpenglow node it costs no extra wait
  /// over `confirmed`, which the Solana Foundation says is removed in a later
  /// release. Where `finalized` trails `confirmed`, on development setups only,
  /// every settled read and `FINALIZED` subscription lags confirmation by about
  /// 32 slots, so a settled websocket await there runs out
  /// `TxMonitorConfig.webSocketConfirmationTimeout` (unless that is set longer
  /// than the lag) and polling settles it. It does so on the `CONFIRMED` status
  /// wherever the read is answered from the processed bank: for every
  /// transaction on a server that ignores a status read's commitment (agave
  /// before 4.4, Surfpool), and on an agave 4.4 test validator on TowerBFT,
  /// which honours it, for a transaction polled with no commitment named. A
  /// transaction that validator is polled at `finalized` for ([#polledSettled])
  /// settles once its block is final, and is resent meanwhile if it opted in.
  /// That is the accepted cost, and the soak starts its validator with
  /// `--alpenglow` for that reason.
  static final Commitment COMMITMENT = FINALIZED;

  /// Whether the commitment monitor polls a transaction's status at [#COMMITMENT]: when
  /// its expiry is verified and neither await is `PROCESSED`. A read at `finalized`
  /// answers nothing for a transaction that has landed and is not final yet, and each of
  /// the other settings asks for what only a node's processed bank answers (see the
  /// class): an unverified expiry makes the first missing status the verdict, and a
  /// `PROCESSED` await is released by a status that is not final. Those are polled with
  /// no commitment named.
  static boolean polledSettled(final Commitment awaitCommitment,
                               final Commitment awaitCommitmentOnError,
                               final boolean verifyExpired) {
    return verifyExpired && awaitCommitment != PROCESSED && awaitCommitmentOnError != PROCESSED;
  }

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
