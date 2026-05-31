package com.apishield.mpc.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.dto.MpcInputRequest;
import com.apishield.mpc.dto.MpcSessionRequest;
import com.apishield.mpc.participant.MpcParticipant;
import com.apishield.mpc.participant.ParticipantCommunicationService;
import com.apishield.mpc.protocol.MpcProtocol;
import com.apishield.mpc.service.MpcCoordinatorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpcCoordinatorServiceImpl implements MpcCoordinatorService {

    private final Map<String, MpcProtocol> protocolRegistry;
    private final ParticipantCommunicationService communicationService;
    private final Map<String, MpcSession> sessionStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Object>>> sessionInputs = new ConcurrentHashMap<>();

    @Override
    public MpcSession createSession(MpcSessionRequest request) {
        MpcProtocol protocol = protocolRegistry.values().stream()
                .filter(p -> p.getProtocolName().equals(request.getProtocolName()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("MPC_001", "不支持的协议: " + request.getProtocolName()));

        MpcSession session = new MpcSession();
        session.setId(IdGenerator.generateId("mpc"));
        session.setSessionId(session.getId());
        session.setProtocolName(request.getProtocolName());
        session.setStatus(MpcSession.SessionStatus.CREATED);
        session.setParticipantIds(request.getParticipantIds());
        session.setCurrentRound(0);
        session.setTotalRounds(1);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        if (request.getParameters() != null) {
            session.getProtocolData().putAll(request.getParameters());
        }

        sessionStore.put(session.getSessionId(), session);
        sessionInputs.put(session.getSessionId(), new ConcurrentHashMap<>());

        log.info("Created MPC session: {}, protocol: {}, participants: {}", 
                session.getSessionId(), request.getProtocolName(), request.getParticipantIds().size());
        return session;
    }

    @Override
    public MpcSession getSession(String sessionId) {
        MpcSession session = sessionStore.get(sessionId);
        if (session == null) {
            throw new BusinessException("NOT_FOUND", "MPC会话不存在: " + sessionId);
        }
        return session;
    }

    @Override
    public MpcSession startSession(String sessionId) {
        MpcSession session = getSession(sessionId);
        MpcProtocol protocol = getProtocol(session.getProtocolName());

        session.setStatus(MpcSession.SessionStatus.INITIALIZING);
        session.setStartTime(LocalDateTime.now());

        List<MpcParticipant> participants = session.getParticipantIds().stream()
                .map(id -> {
                    MpcParticipant p = new MpcParticipant();
                    p.setParticipantId(id);
                    p.setStatus(MpcParticipant.ParticipantStatus.CONNECTED);
                    return p;
                })
                .collect(Collectors.toList());

        protocol.initialize(session, participants);
        session.setStatus(MpcSession.SessionStatus.READY);
        session.setUpdatedAt(LocalDateTime.now());

        log.info("Started MPC session: {}", sessionId);
        return session;
    }

    @Override
    public void submitInput(MpcInputRequest request) {
        MpcSession session = getSession(request.getSessionId());
        if (session.getStatus() != MpcSession.SessionStatus.READY && 
            session.getStatus() != MpcSession.SessionStatus.RUNNING) {
            throw new BusinessException("MPC_001", "会话状态不允许提交输入: " + session.getStatus());
        }

        sessionInputs.get(request.getSessionId())
                     .put(request.getParticipantId(), request.getEncryptedInput());

        log.info("Submitted input from participant {} for session {}", 
                request.getParticipantId(), request.getSessionId());
    }

    @Override
    public MpcSession executeComputation(String sessionId) {
        MpcSession session = getSession(sessionId);
        MpcProtocol protocol = getProtocol(session.getProtocolName());

        Map<String, Map<String, Object>> inputs = sessionInputs.get(sessionId);
        if (inputs.size() < session.getParticipantIds().size()) {
            throw new BusinessException("MPC_001", 
                String.format("输入不完整，需要%d个，已提交%d个", 
                    session.getParticipantIds().size(), inputs.size()));
        }

        session.setStatus(MpcSession.SessionStatus.RUNNING);
        session.setUpdatedAt(LocalDateTime.now());

        List<Map<String, Object>> allInputs = new ArrayList<>(inputs.values());
        Map<String, Object> result = protocol.computeResult(session, allInputs);

        session.setResult(result);
        session.setStatus(MpcSession.SessionStatus.COMPLETED);
        session.setEndTime(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        log.info("Completed MPC computation for session: {}, result: {}", sessionId, result);
        return session;
    }

    @Override
    public MpcSession cancelSession(String sessionId) {
        MpcSession session = getSession(sessionId);
        session.setStatus(MpcSession.SessionStatus.CANCELLED);
        session.setEndTime(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        log.info("Cancelled MPC session: {}", sessionId);
        return session;
    }

    @Override
    public Map<String, Object> getResult(String sessionId) {
        MpcSession session = getSession(sessionId);
        if (session.getStatus() != MpcSession.SessionStatus.COMPLETED) {
            throw new BusinessException("MPC_001", "计算尚未完成，当前状态: " + session.getStatus());
        }
        return session.getResult();
    }

    private MpcProtocol getProtocol(String protocolName) {
        return protocolRegistry.values().stream()
                .filter(p -> p.getProtocolName().equals(protocolName))
                .findFirst()
                .orElseThrow(() -> new BusinessException("MPC_001", "不支持的协议: " + protocolName));
    }
}
