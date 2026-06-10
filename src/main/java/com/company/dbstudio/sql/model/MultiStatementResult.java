package com.company.dbstudio.sql.model;

import java.util.ArrayList;
import java.util.List;

public class MultiStatementResult {

    private final boolean success;
    private final boolean rolledBack;
    private final List<StatementAnalysis> statementAnalyses;
    private final int executedCount;
    private final int successCount;
    private final int failedIndex;
    private final String errorMessage;
    private final String rollbackMessage;

    private MultiStatementResult(Builder builder) {
        this.success = builder.success;
        this.rolledBack = builder.rolledBack;
        this.statementAnalyses = builder.statementAnalyses;
        this.executedCount = builder.executedCount;
        this.successCount = builder.successCount;
        this.failedIndex = builder.failedIndex;
        this.errorMessage = builder.errorMessage;
        this.rollbackMessage = builder.rollbackMessage;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isRolledBack() {
        return rolledBack;
    }

    public List<StatementAnalysis> getStatementAnalyses() {
        return statementAnalyses;
    }

    public int getExecutedCount() {
        return executedCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailedIndex() {
        return failedIndex;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRollbackMessage() {
        return rollbackMessage;
    }

    public boolean hasImplicitCommitWarnings() {
        return statementAnalyses.stream().anyMatch(s -> s.causesImplicitCommit());
    }

    public List<String> getAllWarnings() {
        List<String> allWarnings = new ArrayList<>();
        for (int i = 0; i < statementAnalyses.size(); i++) {
            StatementAnalysis analysis = statementAnalyses.get(i);
            for (String warning : analysis.getWarnings()) {
                allWarnings.add("[语句 " + (i + 1) + "] " + warning);
            }
        }
        return allWarnings;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean success;
        private boolean rolledBack;
        private List<StatementAnalysis> statementAnalyses = new ArrayList<>();
        private int executedCount;
        private int successCount;
        private int failedIndex = -1;
        private String errorMessage;
        private String rollbackMessage;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder rolledBack(boolean rolledBack) {
            this.rolledBack = rolledBack;
            return this;
        }

        public Builder statementAnalyses(List<StatementAnalysis> statementAnalyses) {
            this.statementAnalyses = statementAnalyses;
            return this;
        }

        public Builder executedCount(int executedCount) {
            this.executedCount = executedCount;
            return this;
        }

        public Builder successCount(int successCount) {
            this.successCount = successCount;
            return this;
        }

        public Builder failedIndex(int failedIndex) {
            this.failedIndex = failedIndex;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder rollbackMessage(String rollbackMessage) {
            this.rollbackMessage = rollbackMessage;
            return this;
        }

        public MultiStatementResult build() {
            return new MultiStatementResult(this);
        }
    }
}
