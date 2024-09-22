package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.LongAdder;

public interface TableStats {

  static TableStats createStats(double minEfficientRatio) {
    final var accountSets = new ConcurrentSkipListSet<Set<PublicKey>>((a, b) -> {
      final int sizeCompare = Integer.compare(a.size(), b.size());
      if (sizeCompare == 0) {
        if (a.containsAll(b)) {
          return 0;
        } else {
          return Integer.compare(a.hashCode(), b.hashCode());
        }
      } else {
        return sizeCompare;
      }
    });
    return new TableStatsRecord(
        accountSets,
        new LongAdder(),
        minEfficientRatio,
        new LongAdder()
    );
  }

  boolean addAccountSet(final AddressLookupTable table);

  void reset();
}
