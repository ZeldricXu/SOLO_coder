package com.enterprise.risk.common.exception;

public class RuleCompilationException extends RiskException {

    public RuleCompilationException(String message) {
        super("RULE_COMPILATION_ERROR", message);
    }

    public RuleCompilationException(String message, Throwable cause) {
        super("RULE_COMPILATION_ERROR", message, cause);
    }

    public RuleCompilationException(String message, Object details) {
        super("RULE_COMPILATION_ERROR", message, details);
    }
}
