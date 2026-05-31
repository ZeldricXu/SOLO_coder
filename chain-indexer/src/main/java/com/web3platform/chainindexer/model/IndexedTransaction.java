package com.web3platform.chainindexer.model;

import com.web3platform.persistence.model.entity.ChainTransaction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexedTransaction {

    private ChainTransaction chainTransaction;
    private String decodedInput;
    private String methodName;
    private Map<String, String> params;
}
