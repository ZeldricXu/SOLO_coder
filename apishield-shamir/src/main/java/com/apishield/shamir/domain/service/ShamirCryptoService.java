package com.apishield.shamir.domain.service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Map;

public class ShamirCryptoService {

    private static final BigInteger PRIME = new BigInteger(
        "208351617316091241234326746312124448251235562226470491514186331217050270460481");
    private final SecureRandom random = new SecureRandom();

    public BigInteger[] generateCoefficients(BigInteger secret, int threshold) {
        BigInteger[] coefficients = new BigInteger[threshold];
        coefficients[0] = secret;
        for (int i = 1; i < threshold; i++) {
            coefficients[i] = new BigInteger(PRIME.bitLength(), random).mod(PRIME);
        }
        return coefficients;
    }

    public BigInteger evaluatePolynomial(BigInteger[] coefficients, BigInteger x) {
        BigInteger result = BigInteger.ZERO;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = result.multiply(x).add(coefficients[i]).mod(PRIME);
        }
        return result;
    }

    public BigInteger lagrangeInterpolation(BigInteger[] x, BigInteger[] y, BigInteger targetX) {
        BigInteger result = BigInteger.ZERO;
        for (int i = 0; i < x.length; i++) {
            BigInteger numerator = BigInteger.ONE;
            BigInteger denominator = BigInteger.ONE;
            for (int j = 0; j < x.length; j++) {
                if (i != j) {
                    numerator = numerator.multiply(targetX.subtract(x[j])).mod(PRIME);
                    denominator = denominator.multiply(x[i].subtract(x[j])).mod(PRIME);
                }
            }
            BigInteger lagrange = numerator.multiply(denominator.modInverse(PRIME)).mod(PRIME);
            result = result.add(y[i].multiply(lagrange)).mod(PRIME);
        }
        return result;
    }

    public BigInteger getPrime() {
        return PRIME;
    }
}
