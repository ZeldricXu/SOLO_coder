package com.chain.infrastructure.txbuilder.strategy;

import com.chain.infrastructure.persistence.entity.MultisigWallet;
import com.chain.infrastructure.txbuilder.dto.TransactionRequest;

import java.util.List;

public interface MultisigStrategy {

    String getName();

    boolean validateSignatures(MultisigWallet wallet, List<String> signatures, TransactionRequest request);

    String combineSignatures(MultisigWallet wallet, List<String> signatures, String unsignedTx);

    int getRequiredSignatures(MultisigWallet wallet);
}
