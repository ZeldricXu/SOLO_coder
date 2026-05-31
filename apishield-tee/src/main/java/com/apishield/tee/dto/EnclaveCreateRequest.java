package com.apishield.tee.dto;

import lombok.Data;
import java.util.Map;

@Data
public class EnclaveCreateRequest {
    private String enclaveName;
    private String enclaveType;
    private String hostId;
    private String hostAddress;
    private int port;
    private Map<String, Object> attributes;
}
