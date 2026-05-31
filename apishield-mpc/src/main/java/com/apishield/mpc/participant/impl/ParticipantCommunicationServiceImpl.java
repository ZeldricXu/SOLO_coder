package com.apishield.mpc.participant.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.mpc.participant.ParticipantCommunicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ParticipantCommunicationServiceImpl implements ParticipantCommunicationService {

    private final Map<String, Map<String, Map<String, Object>>> messageQueue = new ConcurrentHashMap<>();

    @Override
    public void sendMessage(String participantId, String messageType, Map<String, Object> payload) {
        log.debug("Sending message {} to participant {}", messageType, participantId);
        messageQueue.computeIfAbsent(participantId, k -> new ConcurrentHashMap<>())
                   .put(messageType, payload);
    }

    @Override
    public Map<String, Object> receiveMessage(String participantId, String messageType) {
        Map<String, Map<String, Object>> participantQueue = messageQueue.get(participantId);
        if (participantQueue == null) {
            throw new BusinessException("MPC_002", "参与方消息队列不存在: " + participantId);
        }
        return participantQueue.remove(messageType);
    }

    @Override
    public boolean isParticipantAvailable(String participantId) {
        return true;
    }

    @Override
    public void broadcastMessage(List<String> participantIds, String messageType, Map<String, Object> payload) {
        for (String participantId : participantIds) {
            sendMessage(participantId, messageType, payload);
        }
        log.info("Broadcasted message {} to {} participants", messageType, participantIds.size());
    }
}
