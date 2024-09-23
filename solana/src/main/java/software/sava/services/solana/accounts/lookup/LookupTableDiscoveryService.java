package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.tx.Transaction;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface LookupTableDiscoveryService extends Runnable {

  static Set<PublicKey> distinctAccounts(final Transaction transaction) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(LookupTableDiscoveryServiceImpl.MAX_ACCOUNTS_PER_TX);
    final var instructions = transaction.instructions();
    for (final var ix : instructions) {
      for (final var account : ix.accounts()) {
        if (!account.signer() && !account.invoked()) {
          distinctAccounts.add(account.publicKey());
        }
      }
    }
    return distinctAccounts;
  }

  static Set<PublicKey> distinctAccounts(final AccountMeta[] accountMetas, final PublicKey[] programs) {
    final var distinctAccounts = HashSet.<PublicKey>newHashSet(accountMetas.length);
    for (final var account : accountMetas) {
      distinctAccounts.add(account.publicKey());
    }
    for (final var program : programs) {
      distinctAccounts.remove(program);
    }
    return distinctAccounts;
  }

  CompletableFuture<Void> initializedFuture();

  AddressLookupTable[] findOptimalSetOfTables(final Transaction transaction);

  AddressLookupTable[] findOptimalSetOfTables(final Set<PublicKey> accounts);

  default AddressLookupTable[] findOptimalSetOfTables(final AccountMeta[] accountMetas, final PublicKey[] programs) {
    return findOptimalSetOfTables(distinctAccounts(accountMetas, programs));
  }
}
