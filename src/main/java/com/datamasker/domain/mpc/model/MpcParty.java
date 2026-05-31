package com.datamasker.domain.mpc.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MpcParty {

    private String partyId;
    private String sessionId;
    private String encryptedInput;
    private boolean inputCommitted;
    private String resultShare;
    private LocalDateTime joinedAt;
}
