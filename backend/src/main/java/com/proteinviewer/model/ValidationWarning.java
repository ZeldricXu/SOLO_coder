package com.proteinviewer.model;

public class ValidationWarning {

    private int lineNumber;
    private String field;
    private String message;
    private String severity;

    public ValidationWarning() {}

    public ValidationWarning(int lineNumber, String field, String message, String severity) {
        this.lineNumber = lineNumber;
        this.field = field;
        this.message = message;
        this.severity = severity;
    }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
}
