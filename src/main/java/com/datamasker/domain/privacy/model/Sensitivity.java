package com.datamasker.domain.privacy.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Sensitivity {

    private String queryType;

    private double globalSensitivity;

    private double localSensitivity;

    private LocalDateTime computedAt;
}
