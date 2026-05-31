package com.apishield.mpc.dto;

import lombok.Data;
import java.util.Map;

@Data
public class MpcInputRequest {
    private String sessionId;
    private String participantId;
    private Map<String, Object> encryptedInput;
}
