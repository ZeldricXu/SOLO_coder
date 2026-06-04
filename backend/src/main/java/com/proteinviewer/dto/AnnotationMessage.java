package com.proteinviewer.dto;

public class AnnotationMessage {
    public enum OperationType {
        CREATED,
        UPDATED,
        DELETED
    }

    private OperationType operation;
    private AnnotationDto annotation;
    private Long structureId;

    public AnnotationMessage() {}

    public AnnotationMessage(OperationType operation, AnnotationDto annotation, Long structureId) {
        this.operation = operation;
        this.annotation = annotation;
        this.structureId = structureId;
    }

    public OperationType getOperation() { return operation; }
    public void setOperation(OperationType operation) { this.operation = operation; }
    public AnnotationDto getAnnotation() { return annotation; }
    public void setAnnotation(AnnotationDto annotation) { this.annotation = annotation; }
    public Long getStructureId() { return structureId; }
    public void setStructureId(Long structureId) { this.structureId = structureId; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final AnnotationMessage r = new AnnotationMessage();
        public Builder operation(OperationType v) { r.operation = v; return this; }
        public Builder annotation(AnnotationDto v) { r.annotation = v; return this; }
        public Builder structureId(Long v) { r.structureId = v; return this; }
        public AnnotationMessage build() { return r; }
    }
}
