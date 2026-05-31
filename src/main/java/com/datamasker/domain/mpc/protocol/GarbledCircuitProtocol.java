package com.datamasker.domain.mpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class GarbledCircuitProtocol {

    public Map<String, String> generateGarbledTable(String gateType) {
        Map<String, String> table = new HashMap<>();

        WireLabel w0Zero = new WireLabel(UUID.randomUUID().toString(), false);
        WireLabel w0One = new WireLabel(UUID.randomUUID().toString(), true);
        WireLabel w1Zero = new WireLabel(UUID.randomUUID().toString(), false);
        WireLabel w1One = new WireLabel(UUID.randomUUID().toString(), true);

        boolean[][] truthTable = getTruthTable(gateType);
        WireLabel[] outLabels = {
                new WireLabel(UUID.randomUUID().toString(), false),
                new WireLabel(UUID.randomUUID().toString(), true)
        };

        WireLabel[][] inputPairs = {
                {w0Zero, w1Zero},
                {w0Zero, w1One},
                {w0One, w1Zero},
                {w0One, w1One}
        };

        for (int i = 0; i < 4; i++) {
            String key = inputPairs[i][0].getValue() + ":" + inputPairs[i][1].getValue();
            int outputBit = truthTable[i][0] ? 1 : 0;
            table.put(key, outLabels[outputBit].getValue());
        }

        return table;
    }

    public String evaluateGate(Map<String, String> garbledTable, String wireLabel1, String wireLabel2) {
        String key = wireLabel1 + ":" + wireLabel2;
        return garbledTable.get(key);
    }

    private boolean[][] getTruthTable(String gateType) {
        return switch (gateType.toUpperCase()) {
            case "AND" -> new boolean[][]{
                    {false, false, false},
                    {false, false, false},
                    {false, false, false},
                    {true, true, true}
            };
            case "OR" -> new boolean[][]{
                    {false, false, false},
                    {true, true, true},
                    {true, true, true},
                    {true, true, true}
            };
            case "XOR" -> new boolean[][]{
                    {false, false, false},
                    {true, true, true},
                    {true, true, true},
                    {false, false, false}
            };
            default -> throw new IllegalArgumentException("Unsupported gate type: " + gateType);
        };
    }

    @Data
    @AllArgsConstructor
    public static class WireLabel {
        private String value;
        private boolean isOne;
    }
}
