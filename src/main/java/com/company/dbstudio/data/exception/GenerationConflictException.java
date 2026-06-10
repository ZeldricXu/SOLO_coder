package com.company.dbstudio.data.exception;

public class GenerationConflictException extends RuntimeException {

    private final long expectedGeneration;
    private final long actualGeneration;
    private final String tableName;

    public GenerationConflictException(String tableName, long expected, long actual) {
        super(String.format("数据版本冲突，表 '%s' 已被修改。期望版本: %d, 当前版本: %d。请刷新后重试。",
                tableName, expected, actual));
        this.tableName = tableName;
        this.expectedGeneration = expected;
        this.actualGeneration = actual;
    }

    public long getExpectedGeneration() {
        return expectedGeneration;
    }

    public long getActualGeneration() {
        return actualGeneration;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isStale() {
        return actualGeneration > expectedGeneration;
    }
}
