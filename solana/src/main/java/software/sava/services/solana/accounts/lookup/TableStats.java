package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface TableStats extends Consumer<AddressLookupTable>, Predicate<AddressLookupTable> {

  static TableStats createStats(double minEfficiencyRatio) {
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
        minEfficiencyRatio,
        new LongAdder(),
        HashMap.newHashMap(1 << 21)
    );
  }

  void reset();

  Map<PublicKey, SingleTableStats> tableStats();
}
