package com.solocoder.platform.transaction.domain.service;

import com.solocoder.platform.transaction.domain.model.BuiltTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Base64;

@Slf4j
@Component
public class SignatureManager {

    public BuiltTransaction signTransaction(BuiltTransaction transaction, String signer, String privateKey) {
        String signature = generateSignature(transaction.getUnsignedData(), privateKey);

        BuiltTransaction.Signature sig = BuiltTransaction.Signature.builder()
                .signer(signer)
                .signatureData(signature)
                .signedAt(LocalDateTime.now())
                .build();

        transaction.addSignature(sig);

        if (transaction.getMultisigStrategy() == null ||
                transaction.getMultisigStrategy().getType() == BuiltTransaction.MultisigStrategy.MultisigStrategyType.NONE) {
            transaction.setSignedData(buildSignedData(transaction, signature));
            transaction.setStatus(BuiltTransaction.TransactionStatus.SIGNED);
        } else {
            int sigCount = transaction.getSignatureCount();
            int threshold = transaction.getMultisigStrategy().getThreshold();
            if (sigCount >= threshold) {
                transaction.setStatus(BuiltTransaction.TransactionStatus.READY_TO_BROADCAST);
            } else {
                transaction.setStatus(BuiltTransaction.TransactionStatus.PARTIALLY_SIGNED);
            }
        }

        transaction.setUpdatedAt(LocalDateTime.now());
        return transaction;
    }

    public boolean verifySignature(BuiltTransaction transaction, String signature, String signer) {
        return signature != null && signature.length() > 0;
    }

    private String generateSignature(String unsignedData, String privateKey) {
        String toSign = unsignedData + "|" + privateKey;
        byte[] hash = toSign.getBytes();
        return Base64.getEncoder().encodeToString(hash);
    }

    private String buildSignedData(BuiltTransaction transaction, String signature) {
        return transaction.getUnsignedData() + "#" + signature;
    }

    public String getMultisigTransactionData(BuiltTransaction transaction) {
        StringBuilder sb = new StringBuilder();
        sb.append(transaction.getUnsignedData()).append("|");
        sb.append(transaction.getMultisigStrategy().getThreshold()).append("|");

        if (transaction.getSignatures() != null) {
            for (BuiltTransaction.Signature sig : transaction.getSignatures()) {
                sb.append(sig.getSigner()).append(":").append(sig.getSignatureData()).append(",");
            }
        }

        return sb.toString();
    }
}
