package com.web3platform.crosschainbridge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web3platform.crosschainbridge.constant.BridgeConstants;
import com.web3platform.crosschainbridge.model.CrossChainMessage;
import com.web3platform.crosschainbridge.model.VerificationResult;
import com.web3platform.crosschainbridge.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.utils.Numeric;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("merkleProofVerifier")
public class MerkleProofVerifier implements MessageVerifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public VerificationResult verifyMessage(CrossChainMessage message, String proof) {
        try {
            if (message == null) {
                return buildInvalidResult(null, "Message is null");
            }
            if (proof == null || proof.isEmpty()) {
                return buildInvalidResult(message, "Proof is empty");
            }
            if (!CryptoUtils.verifySignature(message)) {
                return buildInvalidResult(message, "Invalid signature");
            }

            byte[] leafHash = CryptoUtils.hashMessage(message);
            Map<String, Object> proofData = parseProof(proof);

            if (!verifyMerkleProof(leafHash, proofData)) {
                return buildInvalidResult(message, "Merkle proof verification failed");
            }

            return VerificationResult.builder()
                    .valid(true)
                    .message(message)
                    .reason("Verification successful")
                    .build();

        } catch (Exception e) {
            log.error("Error verifying Merkle proof for message: {}", messageIdOf(message), e);
            return buildInvalidResult(message, "Verification error: " + e.getMessage());
        }
    }

    private Map<String, Object> parseProof(String proof) throws Exception {
        String normalizedProof = CryptoUtils.normalizeHex(proof);
        byte[] proofBytes = Numeric.hexStringToByteArray(normalizedProof);
        String jsonStr = new String(proofBytes);
        return OBJECT_MAPPER.readValue(jsonStr, new TypeReference<Map<String, Object>>() {});
    }

    private boolean verifyMerkleProof(byte[] leafHash, Map<String, Object> proofData) {
        try {
            Object siblingsObj = proofData.get(BridgeConstants.PROOF_SIBLINGS_KEY);
            Object rootObj = proofData.get(BridgeConstants.PROOF_ROOT_KEY);
            Object indexObj = proofData.get(BridgeConstants.PROOF_INDEX_KEY);

            if (siblingsObj == null || rootObj == null) {
                return false;
            }

            @SuppressWarnings("unchecked")
            List<String> siblingHashes = (List<String>) siblingsObj;
            byte[] expectedRoot = Numeric.hexStringToByteArray((String) rootObj);
            long index = indexObj != null ? ((Number) indexObj).longValue() : 0L;

            byte[] currentHash = leafHash;

            for (String siblingHex : siblingHashes) {
                byte[] sibling = Numeric.hexStringToByteArray(siblingHex);
                byte[] combined = (index % 2 == 0)
                        ? CryptoUtils.combineHashes(currentHash, sibling)
                        : CryptoUtils.combineHashes(sibling, currentHash);
                currentHash = CryptoUtils.keccak256(combined);
                index /= 2;
            }

            return Arrays.equals(currentHash, expectedRoot);
        } catch (Exception e) {
            log.error("Merkle proof verification error: {}", e.getMessage());
            return false;
        }
    }

    public byte[] calculateMerkleRoot(List<byte[]> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return new byte[BridgeConstants.HASH_LENGTH];
        }

        byte[][] level = leaves.toArray(new byte[0][]);

        while (level.length > 1) {
            int nextLevelLength = (level.length + 1) / 2;
            byte[][] nextLevel = new byte[nextLevelLength][];

            for (int i = 0; i < level.length; i += 2) {
                byte[] left = level[i];
                byte[] right = (i + 1 < level.length) ? level[i + 1] : level[i];
                byte[] combined = CryptoUtils.combineHashes(left, right);
                nextLevel[i / 2] = CryptoUtils.keccak256(combined);
            }

            level = nextLevel;
        }

        return level[0];
    }

    private VerificationResult buildInvalidResult(CrossChainMessage message, String reason) {
        return VerificationResult.builder()
                .valid(false)
                .message(message)
                .reason(reason)
                .build();
    }

    private String messageIdOf(CrossChainMessage message) {
        return message != null ? message.getMessageId() : "null";
    }
}
