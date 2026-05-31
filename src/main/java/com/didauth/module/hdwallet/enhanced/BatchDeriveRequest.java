package com.didauth.module.hdwallet.enhanced;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class BatchDeriveRequest implements Serializable {

    private String chainType;
    private String mnemonic;
    private String baseDerivationPath;
    private Integer startIndex = 0;
    private Integer count = 10;
    private String labelPrefix;
    private List<String> tags;
    private String userId;
    private Integer batchSize = 10;
}
