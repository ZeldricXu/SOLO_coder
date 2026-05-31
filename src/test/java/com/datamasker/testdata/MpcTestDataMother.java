package com.datamasker.testdata;

import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcParty;
import com.datamasker.domain.mpc.model.MpcSession;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MpcTestDataMother {

    public static final String DEFAULT_PROTOCOL = "SECRET_SHARING";
    public static final int DEFAULT_PARTY_COUNT = 3;
    public static final long DEFAULT_TIMEOUT = 5000;
    public static final BigInteger DEFAULT_MODULUS = new BigInteger("208351617316091241234326746312124448251235562226470491514186331217050270460481");
    private static final SecureRandom RANDOM = new SecureRandom();

    public static MpcSession session() {
        MpcSession session = new MpcSession();
        session.setSessionId("session-" + System.currentTimeMillis());
        session.setProtocolType(DEFAULT_PROTOCOL);
        session.setPartyCount(DEFAULT_PARTY_COUNT);
        session.setStatus("INITIALIZED");
        session.setParties(new ArrayList<>());
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    public static MpcSession sessionWithStatus(String status) {
        MpcSession session = session();
        session.setStatus(status);
        return session;
    }

    public static MpcSession sessionWithAllInputs() {
        MpcSession session = session();
        session.setStatus("AWAITING_INPUTS");
        for (int i = 1; i <= DEFAULT_PARTY_COUNT; i++) {
            session.getParties().add(party("party-" + i, session.getSessionId(), true));
        }
        return session;
    }

    public static MpcParty party(String partyId, String sessionId, boolean committed) {
        MpcParty party = new MpcParty();
        party.setPartyId(partyId);
        party.setSessionId(sessionId);
        party.setEncryptedInput(committed ? "encrypted-data-" + partyId : null);
        party.setInputCommitted(committed);
        party.setJoinedAt(LocalDateTime.now());
        return party;
    }

    public static List<BigInteger> additiveSecretShares(int count, BigInteger secret, BigInteger modulus) {
        List<BigInteger> shares = new ArrayList<>();
        BigInteger sum = BigInteger.ZERO;
        for (int i = 0; i < count - 1; i++) {
            BigInteger share = new BigInteger(modulus.bitLength() - 1, RANDOM).mod(modulus);
            shares.add(share);
            sum = sum.add(share).mod(modulus);
        }
        shares.add(secret.subtract(sum).mod(modulus));
        return shares;
    }

    public static MpcComputationResult computationResult() {
        MpcComputationResult result = new MpcComputationResult();
        result.setSessionId("session-result-001");
        result.setResult("42");
        result.setParticipantCount(DEFAULT_PARTY_COUNT);
        result.setCompletedAt(LocalDateTime.now());
        result.setVerified(true);
        return result;
    }

    public static MpcSessionBuilder sessionBuilder() {
        return new MpcSessionBuilder();
    }

    public static class MpcSessionBuilder {
        private String protocolType = DEFAULT_PROTOCOL;
        private int partyCount = DEFAULT_PARTY_COUNT;
        private String status = "INITIALIZED";
        private List<MpcParty> parties = new ArrayList<>();

        public MpcSessionBuilder withProtocol(String protocol) {
            this.protocolType = protocol;
            return this;
        }

        public MpcSessionBuilder withPartyCount(int count) {
            this.partyCount = count;
            return this;
        }

        public MpcSessionBuilder withStatus(String status) {
            this.status = status;
            return this;
        }

        public MpcSessionBuilder addParties(int count, boolean committed) {
            for (int i = 1; i <= count; i++) {
                parties.add(party("party-" + i, "session-builder", committed));
            }
            return this;
        }

        public MpcSession build() {
            MpcSession session = new MpcSession();
            session.setSessionId("session-" + System.nanoTime());
            session.setProtocolType(protocolType);
            session.setPartyCount(partyCount);
            session.setStatus(status);
            session.setParties(parties);
            session.setCreatedAt(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());
            return session;
        }
    }
}
