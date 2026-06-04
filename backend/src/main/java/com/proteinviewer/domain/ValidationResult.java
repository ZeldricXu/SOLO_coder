package com.proteinviewer.domain;

import java.util.List;

public final class ValidationResult {
    private final boolean valid;
    private final List<ValidationWarning> warnings;

    public ValidationResult(boolean valid, List<ValidationWarning> warnings) {
        this.valid = valid;
        this.warnings = warnings;
    }

    public boolean isValid() { return valid; }
    public List<ValidationWarning> getWarnings() { return warnings; }
}
