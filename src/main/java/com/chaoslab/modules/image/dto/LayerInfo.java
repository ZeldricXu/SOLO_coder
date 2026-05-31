package com.chaoslab.modules.image.dto;

import lombok.Data;

@Data
public class LayerInfo {
    private String digest;
    private Long sizeBytes;
    private String mediaType;
    private String status;
    private Boolean cached;
    private Boolean downloadedViaP2p;
}
