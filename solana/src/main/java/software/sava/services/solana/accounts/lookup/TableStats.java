package software.sava.services.solana.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

public interface TableStats extends Predicate<AddressLookupTable> {

  static TableStats createStats(int minAccountsPerTable, double minEfficiencyRatio) {
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
        minAccountsPerTable,
        minEfficiencyRatio,
        new LongAdder(),
        new LongAdder(),
        new LongAdder(),
        new LongAdder(),
        HashMap.newHashMap(1 << 21)
    );
  }

  static int median(final int[] array) {
    return array[(array.length & 1) == 1 ? array.length / 2 : (array.length / 2) - 1];
  }

  static long median(final long[] array) {
    return array[(array.length & 1) == 1 ? array.length / 2 : (array.length / 2) - 1];
  }

  static double median(final double[] array) {
    return array[(array.length & 1) == 1 ? array.length / 2 : (array.length / 2) - 1];
  }

  TableStatsSummary summarize();

  void reset();

  Map<PublicKey, SingleTableStats> tableStats();
}
