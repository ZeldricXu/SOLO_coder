package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkedUploadRequest {

    private byte[] chunkData;
    private int chunkIndex;
    private int totalChunks;
    private String uploadId;
    private String fileName;
    private String storageType;
    private boolean pin;
}
