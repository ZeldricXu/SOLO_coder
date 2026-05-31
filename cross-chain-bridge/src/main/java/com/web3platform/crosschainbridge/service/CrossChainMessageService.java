package com.web3platform.crosschainbridge.service;

import com.web3platform.crosschainbridge.exception.BridgeErrorCode;
import com.web3platform.crosschainbridge.exception.BridgeException;
import com.web3platform.crosschainbridge.model.CrossChainMessage;
import com.web3platform.crosschainbridge.model.LockRequest;
import com.web3platform.crosschainbridge.pool.MessageSignerPool;
import com.web3platform.crosschainbridge.util.CryptoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.utils.Numeric;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossChainMessageService {

    private final MessageSignerPool messageSignerPool;

    public CrossChainMessage createMessage(LockRequest lockRequest, long nonce) {
        String messageId = generateMessageId();
        long timestamp = System.currentTimeMillis();

        return CrossChainMessage.builder()
                .messageId(messageId)
                .sourceChain(lockRequest.getSourceChain())
                .targetChain(lockRequest.getTargetChain())
                .sender(lockRequest.getLockerAddress())
                .recipient(lockRequest.getLockerAddress())
                .amount(lockRequest.getAmount())
                .nonce(nonce)
                .timestamp(timestamp)
                .signature(null)
                .build();
    }

    public CrossChainMessage signMessage(CrossChainMessage message, String privateKey) {
        Credentials credentials = null;
        try {
            byte[] messageHash = CryptoUtils.hashMessage(message);
            credentials = messageSignerPool.borrowSigner(privateKey);
            String signatureHex = CryptoUtils.signMessage(messageHash, credentials);
            message.setSignature(signatureHex);
            log.debug("Message signed successfully: {}", message.getMessageId());
            return message;
        } catch (Exception e) {
            log.error("Error signing message: {}", message.getMessageId(), e);
            throw new BridgeException(BridgeErrorCode.SIGNING_FAILED,
                    "Failed to sign message: " + e.getMessage(), e);
        } finally {
            if (credentials != null) {
                messageSignerPool.returnSigner(privateKey, credentials);
            }
        }
    }

    public byte[] hashMessage(CrossChainMessage message) {
        return CryptoUtils.hashMessage(message);
    }

    public String hashMessageAsHex(CrossChainMessage message) {
        return Numeric.toHexString(CryptoUtils.hashMessage(message));
    }

    public boolean verifySignature(CrossChainMessage message) {
        return CryptoUtils.verifySignatureFull(message);
    }

    private String generateMessageId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
