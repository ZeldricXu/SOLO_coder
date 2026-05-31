package com.web3platform.crosschainbridge.util;

import com.web3platform.crosschainbridge.constant.BridgeConstants;
import com.web3platform.crosschainbridge.model.CrossChainMessage;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;

public final class CryptoUtils {

    private static final ThreadLocal<Keccak.Digest256> KECCAK_CACHE = ThreadLocal.withInitial(Keccak.Digest256::new);

    private CryptoUtils() {}

    public static byte[] keccak256(byte[] input) {
        Keccak.Digest256 keccak = KECCAK_CACHE.get();
        try {
            return keccak.digest(input);
        } finally {
            keccak.reset();
        }
    }

    public static byte[] hashMessage(CrossChainMessage message) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(message.getMessageId());
        sb.append(message.getSourceChain());
        sb.append(message.getTargetChain());
        sb.append(message.getSender());
        sb.append(message.getRecipient());
        sb.append(message.getAmount().toString());
        sb.append(message.getNonce());
        sb.append(message.getTimestamp());
        return keccak256(sb.toString().getBytes());
    }

    public static boolean verifySignature(CrossChainMessage message) {
        if (message.getSignature() == null || message.getSignature().isEmpty()) {
            return false;
        }
        try {
            byte[] signatureBytes = Numeric.hexStringToByteArray(message.getSignature());
            return signatureBytes.length == BridgeConstants.SIGNATURE_LENGTH;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verifySignatureFull(CrossChainMessage message) {
        if (message.getSignature() == null || message.getSignature().isEmpty()) {
            return false;
        }
        try {
            byte[] messageHash = hashMessage(message);
            byte[] signatureBytes = Numeric.hexStringToByteArray(message.getSignature());

            if (signatureBytes.length != BridgeConstants.SIGNATURE_LENGTH) {
                return false;
            }

            byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);
            byte v = signatureBytes[64];

            Sign.SignatureData signatureData = new Sign.SignatureData(v, r, s);
            BigInteger publicKey = Sign.signedMessageHashToKey(messageHash, signatureData);

            return publicKey != null && publicKey.signum() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String signMessage(byte[] messageHash, Credentials credentials) {
        ECKeyPair keyPair = credentials.getEcKeyPair();
        Sign.SignatureData signatureData = Sign.signMessage(messageHash, keyPair);

        byte[] signatureBytes = new byte[BridgeConstants.SIGNATURE_LENGTH];
        System.arraycopy(signatureData.getR(), 0, signatureBytes, 0, 32);
        System.arraycopy(signatureData.getS(), 0, signatureBytes, 32, 32);
        signatureBytes[64] = signatureData.getV()[0];

        return Numeric.toHexString(signatureBytes);
    }

    public static byte[] keyToNibbles(byte[] key) {
        byte[] nibbles = new byte[key.length * 2];
        for (int i = 0; i < key.length; i++) {
            nibbles[i * 2] = (byte) ((key[i] >> 4) & 0x0F);
            nibbles[i * 2 + 1] = (byte) (key[i] & 0x0F);
        }
        return nibbles;
    }

    public static byte[] combineHashes(byte[] left, byte[] right) {
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left, 0, combined, 0, left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return combined;
    }

    public static String normalizeHex(String hex) {
        if (hex == null) {
            return null;
        }
        return hex.startsWith(BridgeConstants.HEX_PREFIX)
                ? hex.substring(2)
                : hex;
    }

    public static boolean isEVMChain(String chain) {
        if (chain == null) {
            return false;
        }
        String lowerChain = chain.toLowerCase();
        for (String evmChain : BridgeConstants.EVM_CHAINS) {
            if (lowerChain.equals(evmChain) || lowerChain.startsWith(evmChain + "-")) {
                return true;
            }
        }
        return lowerChain.startsWith("evm-");
    }

    public static void clearThreadLocals() {
        KECCAK_CACHE.remove();
    }
}
