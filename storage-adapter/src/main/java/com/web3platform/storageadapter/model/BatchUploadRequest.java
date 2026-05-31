package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchUploadRequest {

    private List<BatchUploadItem> items;
    private String storageType;
    private boolean pin;
    @Builder.Default
    private int concurrency = 3;
}
