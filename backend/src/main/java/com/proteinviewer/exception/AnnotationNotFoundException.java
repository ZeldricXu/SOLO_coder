package com.proteinviewer.exception;

public class AnnotationNotFoundException extends RuntimeException {
    public AnnotationNotFoundException(Long id) {
        super("Annotation not found with id: " + id);
    }
}
