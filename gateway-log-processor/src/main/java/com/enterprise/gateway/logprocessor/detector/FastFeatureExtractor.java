package com.enterprise.gateway.logprocessor.detector;

import com.enterprise.gateway.logprocessor.model.LogFeature;

public class FastFeatureExtractor {

    private static final int FIRST_FIELD_PATTERN_LENGTH = 16;

    public LogFeature extract(byte[] line, int offset, int length) {
        if (line == null) {
            throw new IllegalArgumentException("line cannot be null");
        }
        if (offset < 0 || offset >= line.length) {
            throw new IllegalArgumentException("offset is out of bounds");
        }
        if (length < 0 || offset + length > line.length) {
            throw new IllegalArgumentException("length is out of bounds");
        }
        if (length == 0) {
            throw new IllegalArgumentException("length cannot be zero");
        }

        int colonCount = 0;
        int bracketCount = 0;
        int quoteCount = 0;
        int braceCount = 0;
        int pipeCount = 0;
        int spaceCount = 0;

        byte firstByte = line[offset];
        boolean startsWithDigit = firstByte >= '0' && firstByte <= '9';

        int patternLength = Math.min(FIRST_FIELD_PATTERN_LENGTH, length);
        byte[] firstFieldPattern = new byte[patternLength];

        int end = offset + length;
        for (int i = offset, patternIndex = 0; i < end; i++) {
            byte b = line[i];

            switch (b) {
                case ':':
                    colonCount++;
                    break;
                case '[':
                    bracketCount++;
                    break;
                case '"':
                    quoteCount++;
                    break;
                case '{':
                    braceCount++;
                    break;
                case '|':
                    pipeCount++;
                    break;
                case ' ':
                    spaceCount++;
                    break;
                default:
                    break;
            }

            if (patternIndex < patternLength) {
                firstFieldPattern[patternIndex++] = b;
            }
        }

        double spaceRatio = length > 0 ? (double) spaceCount / length : 0.0;

        return new LogFeature(
                firstByte,
                startsWithDigit,
                colonCount,
                bracketCount,
                quoteCount,
                braceCount,
                pipeCount,
                spaceCount,
                spaceRatio,
                firstFieldPattern
        );
    }

}
