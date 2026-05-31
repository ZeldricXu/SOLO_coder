package com.chain.infrastructure.storageadapter.dto;

import lombok.Data;
import java.util.Map;

@Data
public class StoreRequest {

    private String storageNetwork;

    private byte[] content;

    private String contentType;

    private String filename;

    private Map<String, Object> metadata;

    private Boolean pin;
}
