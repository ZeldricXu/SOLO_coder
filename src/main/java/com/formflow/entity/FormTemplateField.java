package com.formflow.entity;

import com.formflow.enums.FieldType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;

@Data
@Embeddable
public class FormTemplateField {

    @Column(name = "field_id", nullable = false)
    private String fieldId;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false)
    private FieldType fieldType;

    @Column(name = "required")
    private Boolean required = false;

    @Column(name = "default_value")
    private String defaultValue;

    @Column(name = "validation_rules", length = 2000)
    private String validationRules;

    @Column(name = "options", length = 2000)
    private String options;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "placeholder")
    private String placeholder;
}
