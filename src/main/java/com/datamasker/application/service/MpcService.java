package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcParty;
import com.datamasker.domain.mpc.model.MpcSession;
import com.datamasker.domain.mpc.protocol.SecretSharingProtocol;
import com.datamasker.domain.mpc.protocol.GarbledCircuitProtocol;
import com.datamasker.infrastructure.config.MpcConfig;
import com.datamasker.infrastructure.persistence.entity.MpcSessionEntity;
import com.datamasker.infrastructure.persistence.mapper.MpcSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class MpcService {

    private final SecretSharingProtocol secretSharingProtocol;
    private final GarbledCircuitProtocol garbledCircuitProtocol;
    private final MpcConfig mpcConfig;
    private final MpcSessionMapper mpcSessionMapper;

    private final ConcurrentHashMap<String, MpcSession> sessionCache = new ConcurrentHashMap<>();

    public MpcSession createSession(String protocolType, int partyCount) {
        if (partyCount < 2) {
            throw new IllegalArgumentException("Party count must be at least 2");
        }
        if (partyCount > mpcConfig.getMaxParties()) {
            throw new IllegalArgumentException("Party count exceeds maximum: " + mpcConfig.getMaxParties());
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");

        MpcSession session = new MpcSession();
        session.setSessionId(sessionId);
        session.setProtocolType(protocolType);
        session.setPartyCount(partyCount);
        session.setStatus("INITIALIZED");
        session.setParties(new ArrayList<>());
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        MpcSessionEntity entity = new MpcSessionEntity();
        entity.setSessionId(sessionId);
        entity.setProtocolType(protocolType);
        entity.setPartyCount(partyCount);
        entity.setStatus("INITIALIZED");
        entity.setCreatedAt(session.getCreatedAt());
        entity.setUpdatedAt(session.getUpdatedAt());
        mpcSessionMapper.insert(entity);

        sessionCache.put(sessionId, session);

        return session;
    }

    public MpcSession submitInput(String sessionId, String partyId, String encryptedInput) {
        MpcSession session = findSessionOrThrow(sessionId);

        if (!"INITIALIZED".equals(session.getStatus()) && !"AWAITING_INPUTS".equals(session.getStatus())) {
            throw new IllegalStateException("Session is not accepting inputs, current status: " + session.getStatus());
        }

        MpcParty party = new MpcParty();
        party.setPartyId(partyId);
        party.setSessionId(sessionId);
        party.setEncryptedInput(encryptedInput);
        party.setInputCommitted(true);
        party.setJoinedAt(LocalDateTime.now());

        session.getParties().add(party);
        session.setStatus("AWAITING_INPUTS");
        session.setUpdatedAt(LocalDateTime.now());

        if (session.getParties().size() >= session.getPartyCount()) {
            session.setStatus("COMPUTING");
        }

        updateEntityStatus(session);

        return session;
    }

    public MpcComputationResult executeComputation(String sessionId) {
        MpcSession session = findSessionOrThrow(sessionId);

        if (!"COMPUTING".equals(session.getStatus())) {
            throw new IllegalStateException("Session is not in COMPUTING state, current status: " + session.getStatus());
        }

        String result;
        boolean verified;

        if ("SECRET_SHARING".equalsIgnoreCase(session.getProtocolType())) {
            result = executeSecretSharing(session);
            verified = true;
        } else if ("GARBLED_CIRCUIT".equalsIgnoreCase(session.getProtocolType())) {
            result = executeGarbledCircuit(session);
            verified = true;
        } else {
            result = executeSecretSharing(session);
            verified = true;
        }

        session.setStatus("COMPLETED");
        session.setEncryptedResult(result);
        session.setUpdatedAt(LocalDateTime.now());
        updateEntityStatus(session);

        MpcComputationResult computationResult = new MpcComputationResult();
        computationResult.setSessionId(sessionId);
        computationResult.setResult(result);
        computationResult.setParticipantCount(session.getParties().size());
        computationResult.setCompletedAt(LocalDateTime.now());
        computationResult.setVerified(verified);

        return computationResult;
    }

    public MpcComputationResult getResult(String sessionId) {
        MpcSession session = findSessionOrThrow(sessionId);

        if (!"COMPLETED".equals(session.getStatus())) {
            throw new IllegalStateException("Session has not completed computation, current status: " + session.getStatus());
        }

        MpcComputationResult result = new MpcComputationResult();
        result.setSessionId(sessionId);
        result.setResult(session.getEncryptedResult());
        result.setParticipantCount(session.getParties().size());
        result.setCompletedAt(session.getUpdatedAt());
        result.setVerified(true);

        return result;
    }

    public MpcSession findSessionOrThrow(String sessionId) {
        MpcSession cached = sessionCache.get(sessionId);
        if (cached != null) {
            return cached;
        }

        LambdaQueryWrapper<MpcSessionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MpcSessionEntity::getSessionId, sessionId);
        MpcSessionEntity entity = mpcSessionMapper.selectOne(wrapper);

        if (entity == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        MpcSession session = new MpcSession();
        session.setSessionId(entity.getSessionId());
        session.setProtocolType(entity.getProtocolType());
        session.setPartyCount(entity.getPartyCount() != null ? entity.getPartyCount() : 0);
        session.setStatus(entity.getStatus());
        session.setEncryptedResult(entity.getResultEncrypted());
        session.setParties(new ArrayList<>());
        session.setCreatedAt(entity.getCreatedAt());
        session.setUpdatedAt(entity.getUpdatedAt());

        sessionCache.put(sessionId, session);

        return session;
    }

    private String executeSecretSharing(MpcSession session) {
        BigInteger modulus = BigInteger.probablePrime(256, new java.security.SecureRandom());
        List<List<BigInteger>> allPartyShares = new ArrayList<>();

        for (MpcParty party : session.getParties()) {
            BigInteger inputValue = new BigInteger(party.getEncryptedInput());
            List<BigInteger> shares = secretSharingProtocol.splitInput(inputValue, session.getPartyCount(), modulus);
            party.setResultShare(shares.get(0).toString());
            allPartyShares.add(shares);
        }

        List<BigInteger> computedShares = secretSharingProtocol.computeOnShares(allPartyShares, modulus);
        BigInteger reconstructed = secretSharingProtocol.reconstructResult(computedShares, modulus);
        return reconstructed.toString();
    }

    private String executeGarbledCircuit(MpcSession session) {
        Map<String, String> andTable = garbledCircuitProtocol.generateGarbledTable("AND");
        Map<String, String> xorTable = garbledCircuitProtocol.generateGarbledTable("XOR");

        String intermediate = null;
        for (MpcParty party : session.getParties()) {
            if (intermediate == null) {
                intermediate = party.getEncryptedInput();
            } else {
                intermediate = garbledCircuitProtocol.evaluateGate(xorTable, intermediate, party.getEncryptedInput());
            }
        }

        return intermediate != null ? intermediate : "0";
    }

    private void updateEntityStatus(MpcSession session) {
        LambdaQueryWrapper<MpcSessionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MpcSessionEntity::getSessionId, session.getSessionId());
        MpcSessionEntity entity = mpcSessionMapper.selectOne(wrapper);

        if (entity != null) {
            entity.setStatus(session.getStatus());
            entity.setResultEncrypted(session.getEncryptedResult());
            entity.setUpdatedAt(session.getUpdatedAt());
            mpcSessionMapper.updateById(entity);
        }
    }
}
