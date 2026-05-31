package com.web3platform.txbuilder.service;

import com.web3platform.txbuilder.model.MultisigStrategy;
import com.web3platform.txbuilder.model.TransactionBuildRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigStrategyManager {

    private final Map<String, MultisigStrategy> strategyStore = new ConcurrentHashMap<>();

    public MultisigStrategy createStrategy(MultisigStrategy strategy) {
        log.info("Creating multisig strategy: {}", strategy.getStrategyName());

        validateStrategy(strategy);

        if (strategyStore.containsKey(strategy.getStrategyName())) {
            throw new IllegalArgumentException("Strategy with name " + strategy.getStrategyName() + " already exists");
        }

        strategyStore.put(strategy.getStrategyName(), strategy);
        log.info("Multisig strategy created successfully: {}", strategy.getStrategyName());
        return strategy;
    }

    public MultisigStrategy getStrategy(String strategyName) {
        log.info("Getting multisig strategy: {}", strategyName);

        MultisigStrategy strategy = strategyStore.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy not found: " + strategyName);
        }
        return strategy;
    }

    public MultisigStrategy updateStrategy(MultisigStrategy strategy) {
        log.info("Updating multisig strategy: {}", strategy.getStrategyName());

        validateStrategy(strategy);

        if (!strategyStore.containsKey(strategy.getStrategyName())) {
            throw new IllegalArgumentException("Strategy not found: " + strategy.getStrategyName());
        }

        strategyStore.put(strategy.getStrategyName(), strategy);
        log.info("Multisig strategy updated successfully: {}", strategy.getStrategyName());
        return strategy;
    }

    public List<MultisigStrategy> listStrategies() {
        log.info("Listing all multisig strategies");
        return List.copyOf(strategyStore.values());
    }

    public void deleteStrategy(String strategyName) {
        log.info("Deleting multisig strategy: {}", strategyName);

        if (!strategyStore.containsKey(strategyName)) {
            throw new IllegalArgumentException("Strategy not found: " + strategyName);
        }

        strategyStore.remove(strategyName);
        log.info("Multisig strategy deleted successfully: {}", strategyName);
    }

    public TransactionBuildRequest applyStrategy(TransactionBuildRequest request, String strategyName) {
        log.info("Applying multisig strategy {} to transaction", strategyName);

        MultisigStrategy strategy = getStrategy(strategyName);

        if (strategy.getOwners() == null || strategy.getOwners().isEmpty()) {
            throw new IllegalStateException("Strategy has no owners configured");
        }

        if (strategy.getThreshold() <= 0 || strategy.getThreshold() > strategy.getOwners().size()) {
            throw new IllegalStateException("Invalid threshold configuration for strategy: " + strategyName);
        }

        if (strategy.getChainType() != null && !strategy.getChainType().isEmpty()) {
            request.setChainId(strategy.getChainType());
        }

        log.info("Strategy applied: threshold={}, owners={}", strategy.getThreshold(), strategy.getOwners().size());
        return request;
    }

    public boolean isValidSignatureCount(String strategyName, int signatureCount) {
        MultisigStrategy strategy = getStrategy(strategyName);
        return signatureCount >= strategy.getThreshold() && signatureCount <= strategy.getOwners().size();
    }

    public boolean isOwner(String strategyName, String address) {
        MultisigStrategy strategy = getStrategy(strategyName);
        return strategy.getOwners().stream()
                .anyMatch(owner -> owner.equalsIgnoreCase(address));
    }

    public int getRequiredSignatures(String strategyName) {
        return getStrategy(strategyName).getThreshold();
    }

    public List<String> getOwners(String strategyName) {
        return List.copyOf(getStrategy(strategyName).getOwners());
    }

    private void validateStrategy(MultisigStrategy strategy) {
        if (strategy.getStrategyName() == null || strategy.getStrategyName().trim().isEmpty()) {
            throw new IllegalArgumentException("Strategy name cannot be empty");
        }

        if (strategy.getThreshold() <= 0) {
            throw new IllegalArgumentException("Threshold must be greater than 0");
        }

        if (strategy.getOwners() == null || strategy.getOwners().isEmpty()) {
            throw new IllegalArgumentException("Owners list cannot be empty");
        }

        if (strategy.getThreshold() > strategy.getOwners().size()) {
            throw new IllegalArgumentException("Threshold cannot be greater than number of owners");
        }

        long uniqueOwners = strategy.getOwners().stream()
                .map(String::toLowerCase)
                .distinct()
                .count();

        if (uniqueOwners != strategy.getOwners().size()) {
            throw new IllegalArgumentException("Owner list contains duplicates");
        }

        for (String owner : strategy.getOwners()) {
            if (owner == null || owner.trim().isEmpty()) {
                throw new IllegalArgumentException("Owner address cannot be empty");
            }
            if (!isValidEthereumAddress(owner)) {
                throw new IllegalArgumentException("Invalid Ethereum address: " + owner);
            }
        }
    }

    private boolean isValidEthereumAddress(String address) {
        if (address == null) {
            return false;
        }

        String cleanAddress = address.startsWith("0x") ? address.substring(2) : address;

        if (cleanAddress.length() != 40) {
            return false;
        }

        try {
            new java.math.BigInteger(cleanAddress, 16);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
