package com.web3platform.addressmanagement.service;

import com.web3platform.addressmanagement.model.HdWallet;
import com.web3platform.addressmanagement.model.HdWalletResponse;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.MnemonicUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class HdWalletService {

    private final Map<String, HdWallet> walletStore = new ConcurrentHashMap<>();

    public HdWalletResponse createWallet(String mnemonic, String password) {
        String actualMnemonic;
        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            byte[] initialEntropy = new byte[16];
            new SecureRandom().nextBytes(initialEntropy);
            actualMnemonic = MnemonicUtils.generateMnemonic(initialEntropy);
        } else {
            if (!MnemonicUtils.validateMnemonic(mnemonic)) {
                throw new IllegalArgumentException("Invalid mnemonic phrase");
            }
            actualMnemonic = mnemonic;
        }

        byte[] seed = MnemonicUtils.generateSeed(actualMnemonic, password);
        Bip32ECKeyPair masterKeyPair = Bip32ECKeyPair.generateKeyPair(seed);

        String walletId = UUID.randomUUID().toString();
        String seedHex = bytesToHex(seed);
        String masterPublicKey = bytesToHex(masterKeyPair.getPublicKey().toByteArray());
        String chainCode = bytesToHex(masterKeyPair.getChainCode());

        HdWallet wallet = HdWallet.builder()
                .walletId(walletId)
                .mnemonic(actualMnemonic)
                .seedHex(seedHex)
                .masterPublicKey(masterPublicKey)
                .masterPrivateKey(bytesToHex(masterKeyPair.getPrivateKey().toByteArray()))
                .chainCode(chainCode)
                .createdAt(LocalDateTime.now())
                .build();

        walletStore.put(walletId, wallet);

        return HdWalletResponse.builder()
                .walletId(walletId)
                .mnemonic(actualMnemonic)
                .seedHex(seedHex)
                .masterPublicKey(masterPublicKey)
                .chainCode(chainCode)
                .build();
    }

    public HdWalletResponse getWallet(String walletId) {
        HdWallet wallet = walletStore.get(walletId);
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet not found with id: " + walletId);
        }

        return HdWalletResponse.builder()
                .walletId(wallet.getWalletId())
                .seedHex(wallet.getSeedHex())
                .masterPublicKey(wallet.getMasterPublicKey())
                .chainCode(wallet.getChainCode())
                .build();
    }

    public void deleteWallet(String walletId) {
        if (!walletStore.containsKey(walletId)) {
            throw new IllegalArgumentException("Wallet not found with id: " + walletId);
        }
        walletStore.remove(walletId);
    }

    public List<HdWalletResponse> listWallets() {
        return walletStore.values().stream()
                .map(wallet -> HdWalletResponse.builder()
                        .walletId(wallet.getWalletId())
                        .seedHex(wallet.getSeedHex())
                        .masterPublicKey(wallet.getMasterPublicKey())
                        .chainCode(wallet.getChainCode())
                        .build())
                .collect(Collectors.toList());
    }

    public HdWallet getWalletInternal(String walletId) {
        HdWallet wallet = walletStore.get(walletId);
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet not found with id: " + walletId);
        }
        return wallet;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
