package com.web3platform.multisigwallet.service;

import com.web3platform.catalog.application.dto.PagedResult;
import com.web3platform.multisigwallet.config.MultisigWalletConfig;
import com.web3platform.multisigwallet.model.MultisigWallet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigWalletService {

    private final MultisigWalletConfig walletConfig;
    private final Map<String, MultisigWallet> walletStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public MultisigWallet createWallet(List<String> owners, int threshold, String chainType) {
        log.info("Creating multisig wallet with {} owners, threshold: {}, chain: {}", owners.size(), threshold, chainType);

        if (owners == null || owners.isEmpty()) {
            throw new IllegalArgumentException("Owners list cannot be empty");
        }
        if (threshold <= 0 || threshold > owners.size()) {
            throw new IllegalArgumentException("Invalid threshold: must be between 1 and " + owners.size());
        }
        if (chainType == null || !walletConfig.getSupportedChains().contains(chainType)) {
            throw new IllegalArgumentException("Unsupported chain type: " + chainType);
        }

        List<String> normalizedOwners = owners.stream()
                .map(String::toLowerCase)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        String walletAddress = generateWalletAddress(normalizedOwners, threshold, chainType);

        MultisigWallet wallet = MultisigWallet.builder()
                .walletAddress(walletAddress)
                .chainType(chainType)
                .owners(normalizedOwners)
                .threshold(threshold)
                .nonce(0)
                .build();

        walletStore.put(walletAddress.toLowerCase(), wallet);
        log.info("Multisig wallet created at address: {}", walletAddress);
        return wallet;
    }

    public MultisigWallet getWallet(String walletAddress) {
        if (walletAddress == null) {
            return null;
        }
        return walletStore.get(walletAddress.toLowerCase());
    }

    public boolean updateThreshold(String walletAddress, int newThreshold) {
        log.info("Updating threshold for wallet: {} to {}", walletAddress, newThreshold);

        MultisigWallet wallet = getWallet(walletAddress);
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet not found: " + walletAddress);
        }
        if (newThreshold <= 0 || newThreshold > wallet.getOwners().size()) {
            throw new IllegalArgumentException("Invalid threshold: must be between 1 and " + wallet.getOwners().size());
        }

        wallet.setThreshold(newThreshold);
        wallet.setNonce(wallet.getNonce() + 1);
        walletStore.put(walletAddress.toLowerCase(), wallet);
        log.info("Threshold updated successfully for wallet: {}", walletAddress);
        return true;
    }

    private String generateWalletAddress(List<String> owners, int threshold, String chainType) {
        try {
            String input = String.join("|", owners) + "|" + threshold + "|" + chainType + "|" + System.nanoTime();
            byte[] hash = org.web3j.crypto.Hash.sha256(input.getBytes());
            BigInteger key = new BigInteger(1, hash);

            BigInteger publicKey = Sign.publicKeyFromPrivate(key);
            String address = Keys.getAddress(publicKey);

            return "0x" + address.substring(0, 40);
        } catch (Exception e) {
            byte[] randomBytes = new byte[20];
            secureRandom.nextBytes(randomBytes);
            return "0x" + Numeric.toHexStringNoPrefix(randomBytes);
        }
    }

    public PagedResult<MultisigWallet> listWallets(int page, int size) {
        List<MultisigWallet> allWallets = new ArrayList<>(walletStore.values());
        int total = allWallets.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<MultisigWallet> pagedWallets = allWallets.subList(fromIndex, toIndex);

        return new PagedResult<>(pagedWallets, total, page, size);
    }
}
