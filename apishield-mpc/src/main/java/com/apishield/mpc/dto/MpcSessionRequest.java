package com.apishield.mpc.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class MpcSessionRequest {
    private String protocolName;
    private List<String> participantIds;
    private Map<String, Object> parameters;
}
