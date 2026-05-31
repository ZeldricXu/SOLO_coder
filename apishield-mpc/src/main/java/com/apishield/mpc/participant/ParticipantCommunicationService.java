package com.apishield.mpc.participant;

import java.util.Map;

public interface ParticipantCommunicationService {
    void sendMessage(String participantId, String messageType, Map<String, Object> payload);
    Map<String, Object> receiveMessage(String participantId, String messageType);
    boolean isParticipantAvailable(String participantId);
    void broadcastMessage(List<String> participantIds, String messageType, Map<String, Object> payload);
}
