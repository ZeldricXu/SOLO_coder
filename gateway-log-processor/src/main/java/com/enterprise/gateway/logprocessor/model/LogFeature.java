package com.enterprise.gateway.logprocessor.model;

import lombok.Getter;

@Getter
public final class LogFeature {

    private final byte firstByte;

    private final boolean startsWithDigit;

    private final int colonCount;

    private final int bracketCount;

    private final int quoteCount;

    private final int braceCount;

    private final int pipeCount;

    private final int spaceCount;

    private final double spaceRatio;

    private final byte[] firstFieldPattern;

    public LogFeature(byte firstByte,
                      boolean startsWithDigit,
                      int colonCount,
                      int bracketCount,
                      int quoteCount,
                      int braceCount,
                      int pipeCount,
                      int spaceCount,
                      double spaceRatio,
                      byte[] firstFieldPattern) {
        this.firstByte = firstByte;
        this.startsWithDigit = startsWithDigit;
        this.colonCount = colonCount;
        this.bracketCount = bracketCount;
        this.quoteCount = quoteCount;
        this.braceCount = braceCount;
        this.pipeCount = pipeCount;
        this.spaceCount = spaceCount;
        this.spaceRatio = spaceRatio;
        this.firstFieldPattern = firstFieldPattern;
    }

}
