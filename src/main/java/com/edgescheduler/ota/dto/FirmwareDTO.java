package com.edgescheduler.ota.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class FirmwareDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String firmwareId;

    @NotEmpty(message = "firmwareName cannot be empty")
    private String firmwareName;

    @NotEmpty(message = "productKey cannot be empty")
    private String productKey;

    @NotEmpty(message = "version cannot be empty")
    private String version;

    private String filePath;
    private Long fileSize;
    private String md5;
    private String signature;
    private String diffFromVersion;
    private String diffFilePath;
    private String status;
    private String releaseNotes;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
