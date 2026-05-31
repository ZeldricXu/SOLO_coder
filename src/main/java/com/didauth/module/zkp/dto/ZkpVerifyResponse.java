package com.didauth.module.zkp.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ZkpVerifyResponse implements Serializable {

    private String proofId;
    private String circuitId;
    private Boolean verified;
    private String verifyResult;
    private Long verifyTimeMs;
    private String errorMessage;
}
