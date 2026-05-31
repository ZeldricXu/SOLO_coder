package com.datamasker.interfaces.dto.mpc;

import lombok.Data;

@Data
public class CreateSessionRequest {

    private String protocolType;
    private int partyCount;
}
