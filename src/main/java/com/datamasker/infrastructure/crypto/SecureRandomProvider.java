package com.datamasker.infrastructure.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

public class SecureRandomProvider {

    private final SecureRandom secureRandom;

    public SecureRandomProvider() {
        this.secureRandom = new SecureRandom();
    }

    public byte[] nextBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    public BigInteger nextBigInteger(int bitLength) {
        return new BigInteger(bitLength, secureRandom);
    }

    public long nextLong() {
        return secureRandom.nextLong();
    }

    public double nextDouble() {
        return secureRandom.nextDouble();
    }
}
