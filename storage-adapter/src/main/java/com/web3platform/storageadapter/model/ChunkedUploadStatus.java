package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkedUploadStatus {

    private String uploadId;
    private int totalChunks;
    @Builder.Default
    private int uploadedChunks = 0;
    @Builder.Default
    private boolean completed = false;
    @Builder.Default
    private List<String> cids = new ArrayList<>();
    private String finalCid;
    private String storageType;
}
