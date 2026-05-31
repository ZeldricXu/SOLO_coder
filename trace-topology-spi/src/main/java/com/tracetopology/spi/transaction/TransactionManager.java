package com.tracetopology.spi.transaction;

import java.util.function.Supplier;

public interface TransactionManager {

    <T> T executeInTransaction(Supplier<T> action);

    void executeInTransaction(Runnable action);

    <T> T executeInNewTransaction(Supplier<T> action);

    void executeInNewTransaction(Runnable action);

    void rollback();

    void setRollbackOnly();
}
