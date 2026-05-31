package com.apishield.mpc.protocol.impl;

import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.participant.MpcParticipant;
import com.apishield.mpc.protocol.AbstractMpcProtocol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class SecureSumProtocol extends AbstractMpcProtocol {

    private static final BigInteger PRIME = new BigInteger("208351617316091241234326746312124448251235562226470491514186331217050270460481");
    private final Random random = new Random();

    @Override
    public String getProtocolName() {
        return "SECURE_SUM";
    }

    @Override
    public int getMinParticipants() {
        return 2;
    }

    @Override
    public int getMaxParticipants() {
        return 100;
    }

    @Override
    protected void doInitialize(MpcSession session, List<MpcParticipant> participants) {
        session.getProtocolData().put("participantCount", participants.size());
        session.getProtocolData().put("currentRound", 0);
    }

    @Override
    protected void doExecuteRound(MpcSession session, int roundNumber, Map<String, Object> roundData) {
        session.getProtocolData().put("currentRound", roundNumber);
    }

    @Override
    public Map<String, Object> computeResult(MpcSession session, List<Map<String, Object>> allInputs) {
        BigInteger sum = BigInteger.ZERO;
        
        for (Map<String, Object> input : allInputs) {
            Object value = input.get("value");
            if (value instanceof BigInteger) {
                sum = sum.add((BigInteger) value);
            } else if (value instanceof Number) {
                sum = sum.add(BigInteger.valueOf(((Number) value).longValue()));
            }
        }

        BigInteger noise = new BigInteger(PRIME.bitLength(), random).mod(PRIME);
        sum = sum.add(noise).mod(PRIME);

        Map<String, Object> result = new HashMap<>();
        result.put("sum", sum);
        result.put("noise", noise);
        result.put("participantCount", allInputs.size());
        
        log.info("Secure sum computed: {}, participants: {}", sum, allInputs.size());
        return result;
    }

    @Override
    public boolean isCompleted(MpcSession session) {
        return (int) session.getProtocolData().getOrDefault("currentRound", 0) >= 1;
    }
}
