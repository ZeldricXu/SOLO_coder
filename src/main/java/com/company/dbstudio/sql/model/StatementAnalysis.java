package com.company.dbstudio.sql.model;

import java.util.ArrayList;
import java.util.List;

public class StatementAnalysis {

    private final String sql;
    private final String statementType;
    private final boolean isDDL;
    private final boolean causesImplicitCommit;
    private final String description;
    private final List<String> warnings;

    public StatementAnalysis(String sql, String statementType, boolean isDDL,
                             boolean causesImplicitCommit, String description) {
        this.sql = sql;
        this.statementType = statementType;
        this.isDDL = isDDL;
        this.causesImplicitCommit = causesImplicitCommit;
        this.description = description;
        this.warnings = new ArrayList<>();
    }

    public String getSql() {
        return sql;
    }

    public String getStatementType() {
        return statementType;
    }

    public boolean isDDL() {
        return isDDL;
    }

    public boolean causesImplicitCommit() {
        return causesImplicitCommit;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    @Override
    public String toString() {
        return "StatementAnalysis{" +
                "type='" + statementType + '\'' +
                ", isDDL=" + isDDL +
                ", causesImplicitCommit=" + causesImplicitCommit +
                ", warnings=" + warnings.size() +
                '}';
    }
}
