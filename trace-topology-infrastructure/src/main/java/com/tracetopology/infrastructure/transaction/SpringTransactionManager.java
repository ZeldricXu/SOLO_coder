package com.tracetopology.infrastructure.transaction;

import com.tracetopology.spi.transaction.TransactionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

@Slf4j
@Service
public class SpringTransactionManager implements TransactionManager {

    private final PlatformTransactionManager transactionManager;

    public SpringTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    @Override
    public <T> T executeInTransaction(Supplier<T> action) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("tx-" + System.currentTimeMillis());
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            T result = action.get();
            transactionManager.commit(status);
            return result;
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    @Override
    public void executeInTransaction(Runnable action) {
        executeInTransaction(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public <T> T executeInNewTransaction(Supplier<T> action) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("tx-new-" + System.currentTimeMillis());
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            T result = action.get();
            transactionManager.commit(status);
            return result;
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    @Override
    public void executeInNewTransaction(Runnable action) {
        executeInNewTransaction(() -> {
            action.run();
            return null;
        });
    }

    @Override
    public void rollback() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Override
    public void setRollbackOnly() {
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    }
}
