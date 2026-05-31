package com.web3platform.txbuilder.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultisigStrategy {

    private String strategyName;
    private int threshold;
    private List<String> owners;
    private String chainType;
}
