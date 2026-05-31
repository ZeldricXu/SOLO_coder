package com.web3platform.storageadapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinStatus {

    private String cid;
    private String status;
    private String storageType;
}
