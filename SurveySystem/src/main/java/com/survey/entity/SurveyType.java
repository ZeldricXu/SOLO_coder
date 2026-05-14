package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "survey_types")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurveyType {

    @Id
    @Column(name = "type_code", nullable = false, length = 50)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 100)
    private String typeName;

    @Column(name = "type_description", length = 500)
    private String typeDescription;

    @Column(name = "type_status", nullable = false, length = 30)
    private String typeStatus;

    @Column(name = "type_category", length = 50)
    private String typeCategory;

    @Column(name = "type_icon", length = 100)
    private String typeIcon;

    @Column(name = "type_color", length = 20)
    private String typeColor;

    @Column(name = "type_config", length = 2000)
    private String typeConfig;

    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = false;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
