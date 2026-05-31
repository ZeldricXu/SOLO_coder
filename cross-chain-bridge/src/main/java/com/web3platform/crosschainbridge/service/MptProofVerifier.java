package com.web3platform.crosschainbridge.service;

import com.web3platform.crosschainbridge.constant.BridgeConstants;
import com.web3platform.crosschainbridge.exception.BridgeErrorCode;
import com.web3platform.crosschainbridge.exception.BridgeException;
import com.web3platform.crosschainbridge.model.CrossChainMessage;
import com.web3platform.crosschainbridge.model.VerificationResult;
import com.web3platform.crosschainbridge.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

import java.util.List;

@Slf4j
@Component("mptProofVerifier")
public class MptProofVerifier implements MessageVerifier {

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

            byte[] messageHash = CryptoUtils.hashMessage(message);
            byte[] proofBytes = Numeric.hexStringToByteArray(proof);

            if (!verifyMptProof(messageHash, proofBytes)) {
                return buildInvalidResult(message, "MPT proof verification failed");
            }

            return VerificationResult.builder()
                    .valid(true)
                    .message(message)
                    .reason("Verification successful")
                    .build();

        } catch (Exception e) {
            log.error("Error verifying MPT proof for message: {}", messageIdOf(message), e);
            return buildInvalidResult(message, "Verification error: " + e.getMessage());
        }
    }

    private boolean verifyMptProof(byte[] key, byte[] proofBytes) {
        try {
            RlpList proofList = (RlpList) RlpDecoder.decode(proofBytes);
            List<RlpType> nodes = proofList.getValues();

            if (nodes.isEmpty()) {
                return false;
            }

            byte[] currentKey = CryptoUtils.keyToNibbles(key);
            int keyIndex = 0;
            byte[] expectedRoot = null;

            for (int i = 0; i < nodes.size(); i++) {
                RlpType nodeRlp = nodes.get(i);
                byte[] nodeBytes = ((RlpString) nodeRlp).getBytes();
                byte[] nodeHash = CryptoUtils.keccak256(nodeBytes);

                if (i == 0) {
                    expectedRoot = nodeHash;
                }

                RlpList decodedNode = (RlpList) RlpDecoder.decode(nodeBytes);
                List<RlpType> nodeValues = decodedNode.getValues();

                if (isBranchNode(nodeValues)) {
                    keyIndex = processBranchNode(nodeValues, currentKey, keyIndex);
                    if (keyIndex < 0) {
                        return false;
                    }
                    if (keyIndex >= currentKey.length) {
                        return hasValueInBranch(nodeValues);
                    }
                } else if (isExtensionOrLeafNode(nodeValues)) {
                    keyIndex = processExtensionOrLeafNode(nodeValues, currentKey, keyIndex);
                    if (keyIndex < 0) {
                        return false;
                    }
                    if (isLeafNode(nodeValues)) {
                        return keyIndex == currentKey.length && hasLeafValue(nodeValues);
                    }
                } else {
                    return false;
                }
            }

            return keyIndex == currentKey.length;
        } catch (Exception e) {
            log.error("MPT proof parsing error: {}", e.getMessage());
            return false;
        }
    }

    private boolean isBranchNode(List<RlpType> nodeValues) {
        return nodeValues.size() == BridgeConstants.BRANCH_NODE_LENGTH;
    }

    private boolean isExtensionOrLeafNode(List<RlpType> nodeValues) {
        return nodeValues.size() == BridgeConstants.EXTENSION_NODE_LENGTH;
    }

    private int processBranchNode(List<RlpType> nodeValues, byte[] currentKey, int keyIndex) {
        if (keyIndex >= currentKey.length) {
            return keyIndex;
        }

        int nibble = currentKey[keyIndex] & 0xFF;
        if (nibble >= BridgeConstants.BRANCH_NODE_LENGTH - 1) {
            return -1;
        }

        RlpType nextNodeRlp = nodeValues.get(nibble);
        if (isEmptyRlpString(nextNodeRlp)) {
            return -1;
        }

        return keyIndex + 1;
    }

    private boolean hasValueInBranch(List<RlpType> nodeValues) {
        RlpType valueRlp = nodeValues.get(BridgeConstants.BRANCH_NODE_LENGTH - 1);
        byte[] value = ((RlpString) valueRlp).getBytes();
        return value.length > 0;
    }

    private int processExtensionOrLeafNode(List<RlpType> nodeValues, byte[] currentKey, int keyIndex) {
        RlpString pathRlp = (RlpString) nodeValues.get(0);
        byte[] pathBytes = pathRlp.getBytes();
        byte[] nibblePath = extractNibblePath(pathBytes);

        if (!matchPrefix(currentKey, keyIndex, nibblePath)) {
            return -1;
        }

        return keyIndex + nibblePath.length;
    }

    private boolean isLeafNode(List<RlpType> nodeValues) {
        RlpString pathRlp = (RlpString) nodeValues.get(0);
        byte firstByte = pathRlp.getBytes()[0];
        return (firstByte & BridgeConstants.PREFIX_EXTENSION_EVEN) == BridgeConstants.PREFIX_EVEN
                || (firstByte & BridgeConstants.PREFIX_EXTENSION_ODD) == BridgeConstants.PREFIX_ODD;
    }

    private boolean hasLeafValue(List<RlpType> nodeValues) {
        RlpType valueRlp = nodeValues.get(1);
        byte[] value = ((RlpString) valueRlp).getBytes();
        return value.length > 0;
    }

    private boolean isEmptyRlpString(RlpType rlp) {
        return rlp instanceof RlpString && ((RlpString) rlp).getBytes().length == 0;
    }

    private byte[] extractNibblePath(byte[] pathBytes) {
        if (pathBytes == null || pathBytes.length == 0) {
            return new byte[0];
        }

        byte firstByte = pathBytes[0];
        boolean isOdd = (firstByte & BridgeConstants.PREFIX_ODD) == BridgeConstants.PREFIX_ODD;

        byte[] result;
        int startIndex;

        if (isOdd) {
            result = new byte[(pathBytes.length * 2) - 1];
            result[0] = (byte) (firstByte & 0x0F);
            startIndex = 1;
        } else {
            result = new byte[(pathBytes.length - 1) * 2];
            startIndex = 0;
        }

        for (int i = 1; i < pathBytes.length; i++) {
            int resultIndex = startIndex + (i - 1) * 2;
            result[resultIndex] = (byte) ((pathBytes[i] >> 4) & 0x0F);
            result[resultIndex + 1] = (byte) (pathBytes[i] & 0x0F);
        }

        return result;
    }

    private boolean matchPrefix(byte[] key, int keyIndex, byte[] prefix) {
        if (keyIndex + prefix.length > key.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[keyIndex + i] != prefix[i]) {
                return false;
            }
        }
        return true;
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
