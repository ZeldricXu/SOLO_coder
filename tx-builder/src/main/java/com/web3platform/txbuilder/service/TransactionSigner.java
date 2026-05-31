package com.web3platform.txbuilder.service;

import com.web3platform.txbuilder.model.SignResult;
import com.web3platform.txbuilder.util.ChainIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.stereotype.Service;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SignatureException;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionSigner {

    private final ChainIdResolver chainIdResolver;
    private final TransactionEncoder transactionEncoder;

    public SignResult signTransaction(RawTransaction rawTx, String privateKey, String chainId) {
        log.info("Signing transaction for chain: {}", chainId);

        try {
            BigInteger chainIdNum = chainIdResolver.resolveToBigInteger(chainId);
            Credentials credentials = Credentials.create(privateKey);

            byte[] signedMessage;
            if (chainIdNum.longValue() > 0) {
                signedMessage = signEip155Transaction(rawTx, credentials, chainIdNum.longValue());
            } else {
                signedMessage = signLegacyTransaction(rawTx, credentials);
            }

            String signedTxHex = Numeric.toHexString(signedMessage);
            String txHash = transactionEncoder.hash(rawTx);
            String signerAddress = credentials.getAddress();

            SignResult result = SignResult.builder()
                    .signedTxHex(signedTxHex)
                    .txHash(txHash)
                    .signerAddress(signerAddress)
                    .signature(extractSignature(signedMessage))
                    .build();

            log.info("Transaction signed successfully, hash: {}, signer: {}", txHash, signerAddress);
            return result;

        } catch (Exception e) {
            log.error("Failed to sign transaction", e);
            throw new RuntimeException("Failed to sign transaction: " + e.getMessage(), e);
        }
    }

    public SignResult signWithKeystore(RawTransaction rawTx, String keystoreJson, String password) {
        log.info("Signing transaction with keystore");

        try {
            Credentials credentials = WalletUtils.loadJsonCredentials(password, keystoreJson);

            byte[] signedMessage = signLegacyTransaction(rawTx, credentials);
            String signedTxHex = Numeric.toHexString(signedMessage);
            String txHash = transactionEncoder.hash(rawTx);
            String signerAddress = credentials.getAddress();

            return SignResult.builder()
                    .signedTxHex(signedTxHex)
                    .txHash(txHash)
                    .signerAddress(signerAddress)
                    .signature(extractSignature(signedMessage))
                    .build();

        } catch (Exception e) {
            log.error("Failed to sign transaction with keystore", e);
            throw new RuntimeException("Failed to sign transaction with keystore: " + e.getMessage(), e);
        }
    }

    public SignResult signMultisig(RawTransaction rawTx, String ownerPrivateKey) {
        log.info("Signing multisig transaction with owner key");

        try {
            Credentials credentials = Credentials.create(ownerPrivateKey);
            byte[] txHashBytes = Hash.sha3(transactionEncoder.encode(rawTx));
            Sign.SignatureData signatureData = Sign.signMessage(txHashBytes, credentials.getEcKeyPair());

            String signatureHex = Numeric.toHexString(signatureData.getR())
                    + Numeric.toHexStringNoPrefix(signatureData.getS())
                    + Numeric.toHexStringNoPrefix(signatureData.getV());

            return SignResult.builder()
                    .signature(signatureHex)
                    .signedTxHex(Numeric.toHexString(transactionEncoder.encode(rawTx)))
                    .txHash(Numeric.toHexString(txHashBytes))
                    .signerAddress(credentials.getAddress())
                    .build();

        } catch (Exception e) {
            log.error("Failed to sign multisig transaction", e);
            throw new RuntimeException("Failed to sign multisig transaction: " + e.getMessage(), e);
        }
    }

    public boolean verifySignature(String signedTxHex, String expectedSigner) {
        try {
            RawTransaction rawTx = transactionEncoder.decode(signedTxHex);
            byte[] signedMessageBytes = Numeric.hexStringToByteArray(signedTxHex);

            BigInteger publicKey = recoverPublicKey(rawTx, signedMessageBytes);
            String recoveredAddress = "0x" + Keys.getAddress(publicKey);

            return recoveredAddress.equalsIgnoreCase(expectedSigner);

        } catch (Exception e) {
            log.error("Failed to verify signature", e);
            return false;
        }
    }

    public String recoverSigner(String signedTxHex) {
        try {
            byte[] signedMessageBytes = Numeric.hexStringToByteArray(signedTxHex);
            RawTransaction rawTx = transactionEncoder.decode(signedTxHex);
            BigInteger publicKey = recoverPublicKey(rawTx, signedMessageBytes);
            return "0x" + Keys.getAddress(publicKey);
        } catch (Exception e) {
            log.error("Failed to recover signer address", e);
            throw new RuntimeException("Failed to recover signer address: " + e.getMessage(), e);
        }
    }

    private byte[] signLegacyTransaction(RawTransaction rawTx, Credentials credentials) {
        return org.web3j.crypto.TransactionEncoder.signMessage(rawTx, credentials);
    }

    private byte[] signEip155Transaction(RawTransaction rawTx, Credentials credentials, long chainId) {
        return org.web3j.crypto.TransactionEncoder.signMessage(rawTx, chainId, credentials);
    }

    private String extractSignature(byte[] signedMessage) {
        try {
            int vIndex = signedMessage.length - 65;
            byte[] r = new byte[32];
            byte[] s = new byte[32];
            byte v = signedMessage[signedMessage.length - 1];

            System.arraycopy(signedMessage, vIndex, r, 0, 32);
            System.arraycopy(signedMessage, vIndex + 32, s, 0, 32);

            return "0x" + Hex.toHexString(r) + Hex.toHexString(s) + Hex.toHexString(new byte[]{v});
        } catch (Exception e) {
            log.warn("Failed to extract signature", e);
            return "";
        }
    }

    private BigInteger recoverPublicKey(RawTransaction rawTx, byte[] signedMessage) throws SignatureException {
        byte[] rlpEncoded = org.web3j.crypto.TransactionEncoder.encode(rawTx);
        byte[] txHash = Hash.sha3(rlpEncoded);

        int sigLen = 65;
        byte v = signedMessage[signedMessage.length - 1];
        byte[] r = new byte[32];
        byte[] s = new byte[32];
        System.arraycopy(signedMessage, signedMessage.length - sigLen, r, 0, 32);
        System.arraycopy(signedMessage, signedMessage.length - sigLen + 32, s, 0, 32);

        Sign.SignatureData signatureData = new Sign.SignatureData(v, r, s);
        return Sign.signedMessageToKey(txHash, signatureData);
    }
}
