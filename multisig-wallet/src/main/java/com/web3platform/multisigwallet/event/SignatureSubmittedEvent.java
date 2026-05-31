package com.web3platform.multisigwallet.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class SignatureSubmittedEvent extends ApplicationEvent {

    private final Long proposalId;
    private final int currentSignatureCount;
    private final int requiredThreshold;

    public SignatureSubmittedEvent(Object source, Long proposalId, int currentSignatureCount, int requiredThreshold) {
        super(source);
        this.proposalId = proposalId;
        this.currentSignatureCount = currentSignatureCount;
        this.requiredThreshold = requiredThreshold;
    }
}
