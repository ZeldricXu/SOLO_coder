package com.datapipeline.dp.budget;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class PrivacyBudgetManager {

    public enum Status {
        SUFFICIENT,
        WARNING,
        EXHAUSTED
    }

    private final Map<String, BudgetAccount> accounts = new ConcurrentHashMap<>();
    private final double defaultEpsilon;
    private final double defaultDelta;
    private final Duration resetInterval;

    public PrivacyBudgetManager(double defaultEpsilon, double defaultDelta, Duration resetInterval) {
        this.defaultEpsilon = defaultEpsilon;
        this.defaultDelta = defaultDelta;
        this.resetInterval = resetInterval;
    }

    public PrivacyBudgetManager() {
        this(1.0, 1e-5, Duration.ofDays(1));
    }

    public BudgetAccount createAccount(String accountId) {
        return createAccount(accountId, defaultEpsilon, defaultDelta);
    }

    public BudgetAccount createAccount(String accountId, double epsilon, double delta) {
        BudgetAccount account = BudgetAccount.builder()
                .accountId(accountId)
                .totalEpsilon(epsilon)
                .remainingEpsilon(epsilon)
                .totalDelta(delta)
                .remainingDelta(delta)
                .createdAt(Instant.now())
                .lastResetAt(Instant.now())
                .build();
        accounts.put(accountId, account);
        log.info("Privacy budget account created: id={}, epsilon={}, delta={}",
                accountId, epsilon, delta);
        return account;
    }

    public boolean consumeBudget(String accountId, double epsilon, double delta) {
        BudgetAccount account = accounts.get(accountId);
        if (account == null) {
            log.warn("Account not found: id={}", accountId);
            return false;
        }

        checkAndResetIfNeeded(account);

        if (account.getRemainingEpsilon() < epsilon || account.getRemainingDelta() < delta) {
            log.warn("Insufficient privacy budget: account={}, requested epsilon={}, delta={}, " +
                            "remaining epsilon={}, delta={}",
                    accountId, epsilon, delta, account.getRemainingEpsilon(), account.getRemainingDelta());
            return false;
        }

        account.setRemainingEpsilon(account.getRemainingEpsilon() - epsilon);
        account.setRemainingDelta(account.getRemainingDelta() - delta);
        account.getUsageHistory().add(
                new BudgetUsage(epsilon, delta, Instant.now()));

        log.debug("Privacy budget consumed: account={}, epsilon={}, delta={}, remaining={},{}",
                accountId, epsilon, delta,
                account.getRemainingEpsilon(), account.getRemainingDelta());
        return true;
    }

    public boolean consumeBudget(String accountId, double epsilon) {
        return consumeBudget(accountId, epsilon, 0.0);
    }

    public Status getBudgetStatus(String accountId) {
        BudgetAccount account = accounts.get(accountId);
        if (account == null) {
            return Status.EXHAUSTED;
        }

        double epsilonRatio = account.getRemainingEpsilon() / account.getTotalEpsilon();

        if (epsilonRatio <= 0.01) {
            return Status.EXHAUSTED;
        } else if (epsilonRatio <= 0.2) {
            return Status.WARNING;
        }
        return Status.SUFFICIENT;
    }

    public Optional<BudgetAccount> getAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public void resetBudget(String accountId) {
        BudgetAccount account = accounts.get(accountId);
        if (account != null) {
            account.setRemainingEpsilon(account.getTotalEpsilon());
            account.setRemainingDelta(account.getTotalDelta());
            account.setLastResetAt(Instant.now());
            log.info("Privacy budget reset: account={}", accountId);
        }
    }

    public void resetAll() {
        for (BudgetAccount account : accounts.values()) {
            account.setRemainingEpsilon(account.getTotalEpsilon());
            account.setRemainingDelta(account.getTotalDelta());
            account.setLastResetAt(Instant.now());
        }
        log.info("All privacy budgets reset");
    }

    private void checkAndResetIfNeeded(BudgetAccount account) {
        if (resetInterval != null && account.getLastResetAt() != null) {
            Instant nextReset = account.getLastResetAt().plus(resetInterval);
            if (Instant.now().isAfter(nextReset)) {
                account.setRemainingEpsilon(account.getTotalEpsilon());
                account.setRemainingDelta(account.getTotalDelta());
                account.setLastResetAt(Instant.now());
                log.info("Privacy budget auto-reset: account={}", account.getAccountId());
            }
        }
    }

}
