package com.web3platform.multisigwallet.listener;

import com.web3platform.multisigwallet.config.MultisigWalletConfig;
import com.web3platform.multisigwallet.event.SignatureSubmittedEvent;
import com.web3platform.multisigwallet.service.ExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignatureCompleteListener {

    private final ExecutionService executionService;
    private final MultisigWalletConfig walletConfig;

    @Async
    @EventListener
    public void handleSignatureSubmitted(SignatureSubmittedEvent event) {
        log.info("Received signature submitted event for proposal: {}, signatures: {}/{}",
                event.getProposalId(), event.getCurrentSignatureCount(), event.getRequiredThreshold());

        if (!walletConfig.isAutoExecute()) {
            log.debug("Auto-execute is disabled, skipping automatic execution");
            return;
        }

        if (event.getCurrentSignatureCount() >= event.getRequiredThreshold()) {
            log.info("Threshold reached for proposal {}, triggering automatic execution", event.getProposalId());
            try {
                var result = executionService.executeProposal(event.getProposalId());
                if (result.isSuccess()) {
                    log.info("Automatic execution successful for proposal: {}, txHash: {}",
                            event.getProposalId(), result.getTxHash());
                } else {
                    log.warn("Automatic execution failed for proposal: {}, error: {}",
                            event.getProposalId(), result.getError());
                }
            } catch (Exception e) {
                log.error("Error during automatic execution for proposal: {}", event.getProposalId(), e);
            }
        } else {
            log.debug("Not enough signatures yet for proposal: {}, need {} more",
                    event.getProposalId(), event.getRequiredThreshold() - event.getCurrentSignatureCount());
        }
    }
}
