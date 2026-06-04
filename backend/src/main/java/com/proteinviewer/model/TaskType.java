package com.proteinviewer.model;

public enum TaskType {
    BATCH_ANALYSIS("batch_analysis"),
    ELECTROSTATIC_SURFACE("electrostatic_surface"),
    MULTI_STRUCTURE_ALIGNMENT("multi_structure_alignment"),
    DISULFIDE_DETECTION("disulfide_detection"),
    GLYCOSYLATION_PREDICTION("glycosylation_prediction");

    private final String value;

    TaskType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
