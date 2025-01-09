package software.sava.services.solana.transactions;

import software.sava.core.tx.Instruction;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.response.TransactionError;
import software.sava.services.solana.epoch.EpochInfoService;
import software.sava.services.solana.remote.call.RpcCaller;
import software.sava.solana.programs.clients.NativeProgramClient;

import java.util.List;
import java.util.function.Function;

public interface InstructionService {

  static InstructionService createService(final RpcCaller rpcCaller,
                                          final TransactionProcessor transactionProcessor,
                                          final NativeProgramClient nativeProgramClient,
                                          final EpochInfoService epochInfoService,
                                          final TxMonitorService txMonitorService) {
    return new BaseInstructionService(
        rpcCaller,
        transactionProcessor,
        nativeProgramClient,
        epochInfoService,
        txMonitorService
    );
  }

  TransactionError processInstructions(final List<Instruction> instructions, final String logContext) throws InterruptedException;

  TransactionError processInstructions(final List<Instruction> instructions,
                                       final Function<List<Instruction>, Transaction> transactionFactory,
                                       final String logContext) throws InterruptedException;
}
