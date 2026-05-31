package com.web3platform.chaininteraction.service;

import com.web3platform.chaininteraction.model.SubmitResult;
import com.web3platform.chaininteraction.model.TransactionReceipt;
import com.web3platform.chaininteraction.model.UnifiedTransaction;
import com.web3platform.chaininteraction.observability.ChainInteractionMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final ChainClientFactory chainClientFactory;
    private final ChainInteractionMetrics chainInteractionMetrics;

    public SubmitResult submitTransaction(String chainId, String signedTxHex) {
        long startTime = System.currentTimeMillis();
        try {
            ChainClient client = chainClientFactory.getClient(chainId);
            TransactionReceipt receipt = client.submitRawTransaction(chainId, signedTxHex);
            long confirmationDuration = System.currentTimeMillis() - startTime;

            if (receipt.getStatus() == 1) {
                log.info("Transaction submitted successfully: chainId={}, txHash={}", chainId, receipt.getTxHash());
                chainInteractionMetrics.recordTxSubmission(chainId, "SUCCESS");
                chainInteractionMetrics.recordConfirmationDuration(chainId, confirmationDuration);
                return SubmitResult.builder()
                        .txHash(receipt.getTxHash())
                        .success(true)
                        .error(null)
                        .build();
            } else {
                log.warn("Transaction failed on chain: chainId={}, txHash={}", chainId, receipt.getTxHash());
                chainInteractionMetrics.recordTxSubmission(chainId, "FAILED");
                chainInteractionMetrics.recordConfirmationDuration(chainId, confirmationDuration);
                return SubmitResult.builder()
                        .txHash(receipt.getTxHash())
                        .success(false)
                        .error("Transaction reverted on chain")
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to submit transaction: chainId={}", chainId, e);
            chainInteractionMetrics.recordTxSubmission(chainId, "ERROR");
            return SubmitResult.builder()
                    .txHash(null)
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    public UnifiedTransaction getTransactionStatus(String chainId, String txHash) {
        ChainClient client = chainClientFactory.getClient(chainId);
        return client.getTransactionByHash(chainId, txHash);
    }
}
