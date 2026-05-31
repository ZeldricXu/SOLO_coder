package com.datamasker.interfaces.dto.tee;

import lombok.Data;

@Data
public class CreateEnclaveResponse {

    private String enclaveId;

    private String status;

    private String measurementHash;
}
