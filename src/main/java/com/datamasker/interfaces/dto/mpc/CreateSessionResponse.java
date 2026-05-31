package com.datamasker.interfaces.dto.mpc;

import lombok.Data;

@Data
public class CreateSessionResponse {

    private String sessionId;
    private String protocolType;
    private int partyCount;
    private String status;
}
