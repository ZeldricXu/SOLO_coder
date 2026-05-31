package com.datamasker.domain.mpc.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MpcSession {

    private String sessionId;
    private String protocolType;
    private int partyCount;
    private String status;
    private List<MpcParty> parties;
    private String encryptedResult;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
