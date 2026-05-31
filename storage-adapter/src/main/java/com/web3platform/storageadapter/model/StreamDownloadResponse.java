package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamDownloadResponse {

    private InputStream inputStream;
    private long contentLength;
    private String cid;
    private String storageType;
}
