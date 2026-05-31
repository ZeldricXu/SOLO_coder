package com.chaoslab.modules.image.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ImagePullResponse {

    private String imageReference;
    private String registry;
    private String repository;
    private String tag;
    private String manifestDigest;
    private List<LayerInfo> layers;
    private Long totalSizeBytes;
    private BigDecimal downloadProgress;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String status;
    private Boolean usingP2p;
    private Integer seedersCount;
}
