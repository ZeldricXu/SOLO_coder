package com.apishield.mpc.protocol.impl;

import com.apishield.mpc.domain.MpcSession;
import com.apishield.mpc.protocol.AbstractMpcProtocol;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SecureAverageProtocol extends AbstractMpcProtocol {

    @Override
    public String getProtocolName() {
        return "SECURE_AVERAGE";
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
    protected void doInitialize(MpcSession session, List<com.apishield.mpc.participant.MpcParticipant> participants) {
        session.getProtocolData().put("participantCount", participants.size());
    }

    @Override
    protected void doExecuteRound(MpcSession session, int roundNumber, Map<String, Object> roundData) {
    }

    @Override
    public Map<String, Object> computeResult(MpcSession session, List<Map<String, Object>> allInputs) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;

        for (Map<String, Object> input : allInputs) {
            Object value = input.get("value");
            if (value instanceof BigDecimal) {
                sum = sum.add((BigDecimal) value);
            } else if (value instanceof Number) {
                sum = sum.add(BigDecimal.valueOf(((Number) value).doubleValue()));
            }
            count++;
        }

        BigDecimal average = count > 0 ? sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        Map<String, Object> result = new HashMap<>();
        result.put("sum", sum);
        result.put("count", count);
        result.put("average", average);

        log.info("Secure average computed: {}, count: {}", average, count);
        return result;
    }

    @Override
    public boolean isCompleted(MpcSession session) {
        return true;
    }
}
