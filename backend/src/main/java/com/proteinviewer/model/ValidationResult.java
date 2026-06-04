package com.proteinviewer.model;

import java.util.List;

public class ValidationResult {

    private boolean valid;
    private List<ValidationWarning> warnings;

    public ValidationResult() {}

    public ValidationResult(boolean valid, List<ValidationWarning> warnings) {
        this.valid = valid;
        this.warnings = warnings;
    }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public List<ValidationWarning> getWarnings() { return warnings; }
    public void setWarnings(List<ValidationWarning> warnings) { this.warnings = warnings; }

    public void addWarning(int lineNumber, String field, String message, String severity) {
        warnings.add(new ValidationWarning(lineNumber, field, message, severity));
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final ValidationResult r = new ValidationResult();
        public Builder valid(boolean v) { r.valid = v; return this; }
        public Builder warnings(List<ValidationWarning> v) { r.warnings = v; return this; }
        public ValidationResult build() { return r; }
    }
}
