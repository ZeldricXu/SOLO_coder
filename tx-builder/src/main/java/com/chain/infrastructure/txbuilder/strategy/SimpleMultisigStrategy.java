package com.chain.infrastructure.txbuilder.strategy;

import com.chain.infrastructure.persistence.entity.MultisigWallet;
import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SimpleMultisigStrategy implements MultisigStrategy {

    @Override
    public String getName() {
        return "SIMPLE";
    }

    @Override
    public boolean validateSignatures(MultisigWallet wallet, List<String> signatures, TransactionRequest request) {
        return signatures != null && signatures.size() >= wallet.getThreshold();
    }

    @Override
    public String combineSignatures(MultisigWallet wallet, List<String> signatures, String unsignedTx) {
        return String.join(",", signatures);
    }

    @Override
    public int getRequiredSignatures(MultisigWallet wallet) {
        return wallet.getThreshold();
    }
}
