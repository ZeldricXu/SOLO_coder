package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageUploadRequest {

    private byte[] data;
    private String fileName;
    private String storageType;
    private boolean pin;
    private Map<String, String> metadata;
}
