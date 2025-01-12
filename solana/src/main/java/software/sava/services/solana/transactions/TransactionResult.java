package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.rpc.json.http.response.TransactionError;

import java.util.List;

public record TransactionResult(List<Instruction> instructions,
                                TransactionError error,
                                String sig,
                                String formattedSig) {

  static TransactionResult createResult(final List<Instruction> instructions, final TransactionError error) {
    return new TransactionResult(instructions, error, null, null);
  }

  static TransactionResult createResult(final List<Instruction> instructions, final String sig, final String formattedSig) {
    return new TransactionResult(instructions, null, sig, formattedSig);
  }
}
