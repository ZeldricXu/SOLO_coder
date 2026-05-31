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
public class BatchUploadResult {

    @Builder.Default
    private List<StorageUploadResponse> results = new ArrayList<>();
    private int totalCount;
    private int successCount;
    private int failedCount;
    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
