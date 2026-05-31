package com.web3platform.chainindexer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecodeTxRequest {

    private String inputData;
    private String contractAddress;
}
