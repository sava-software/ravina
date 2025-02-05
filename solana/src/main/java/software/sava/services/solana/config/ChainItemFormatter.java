package software.sava.services.solana.config;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.response.TxStatus;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ChainItemFormatter(String sigFormat, String addressFormat) {

  private static final String DEFAULT_SIG_FORMAT = "https://solscan.io/tx/%s";
  private static final String DEFAULT_ADDRESS_FORMAT = "https://solscan.io/account/%s";

  public static ChainItemFormatter createDefault() {
    return new ChainItemFormatter(DEFAULT_SIG_FORMAT, DEFAULT_ADDRESS_FORMAT);
  }

  public static ChainItemFormatter parseFormatter(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public static String commaSeparateInteger(final String integer) {
    final int len = integer.length();
    if (len <= 3) {
      return integer;
    }
    final char[] src = integer.toCharArray();
    final char[] sep = new char[len + (len / 3)];
    for (int i = len - 1, j = sep.length - 1, c = 0; ; --j) {
      sep[j] = src[i];
      if (--i < 0) {
        return j == 0 ? new String(sep) : new String(sep, 1, sep.length - 1);
      }
      if (++c == 3) {
        sep[--j] = ',';
        c = 0;
      }
    }
  }

  public String formatSig(final String sig) {
    return String.format(sigFormat, sig);
  }

  public String formatAddress(final String sig) {
    return String.format(addressFormat, sig);
  }

  public String formatAddress(final PublicKey publicKey) {
    return formatAddress(publicKey.toBase58());
  }

  public String formatSigStatus(final String sig, final TxStatus sigStatus) {
    final var context = sigStatus.context();
    return String.format("""
            
            Sig Status:
              %s
              context slot: %d
              tx slot: %d
              error: %s
              status: %s
              confirmations: %d
            """,
        formatSig(sig),
        context == null ? -1 : context.slot(),
        sigStatus.slot(),
        sigStatus.error(),
        sigStatus.confirmationStatus(),
        sigStatus.confirmations().orElse(-1)
    );
  }

  private static final class Parser implements FieldBufferPredicate {

    private String sigFormat;
    private String addressFormat;

    private ChainItemFormatter create() {
      return new ChainItemFormatter(
          requireNonNullElse(sigFormat, DEFAULT_SIG_FORMAT),
          requireNonNullElse(addressFormat, DEFAULT_ADDRESS_FORMAT)
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("sig", buf, offset, len)) {
        sigFormat = ji.readString();
      } else if (fieldEquals("address", buf, offset, len)) {
        addressFormat = ji.readString();
      } else {
        throw new IllegalStateException("Unknown ChainItemFormatter config field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
