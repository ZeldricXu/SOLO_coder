package com.assetmanage.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MaintenancePlanRequest {

    private String assetId;
    private String maintenanceType;
    private LocalDate maintenanceDate;
    private String maintenanceContent;
    private BigDecimal maintenanceCost;
    private LocalDate nextMaintenance;
}
