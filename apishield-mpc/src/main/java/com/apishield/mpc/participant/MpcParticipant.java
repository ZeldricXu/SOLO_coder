package com.apishield.mpc.participant;

import lombok.Data;
import java.util.Map;

@Data
public class MpcParticipant {
    private String participantId;
    private String name;
    private String endpoint;
    private String publicKey;
    private int partyIndex;
    private Map<String, Object> metadata;
    private ParticipantStatus status;

    public enum ParticipantStatus {
        PENDING, CONNECTED, READY, COMPUTING, COMPLETED, ERROR, TIMEOUT
    }
}
