package com.proteinviewer.domain;

public final class ValidationWarning {
    private final int lineNumber;
    private final String field;
    private final String message;
    private final String severity;

    public ValidationWarning(int lineNumber, String field, String message, String severity) {
        this.lineNumber = lineNumber;
        this.field = field;
        this.message = message;
        this.severity = severity;
    }

    public int getLineNumber() { return lineNumber; }
    public String getField() { return field; }
    public String getMessage() { return message; }
    public String getSeverity() { return severity; }
}
