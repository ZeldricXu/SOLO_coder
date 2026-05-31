package com.edgescheduler.ota.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;

@Data
public class DiffPackageRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "productKey cannot be empty")
    private String productKey;

    @NotEmpty(message = "fromVersion cannot be empty")
    private String fromVersion;

    @NotEmpty(message = "toVersion cannot be empty")
    private String toVersion;

    private String algorithm;
}
