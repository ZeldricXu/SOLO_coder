package com.apishield.security.keysharding.infrastructure;

import com.apishield.security.keysharding.domain.KeyShare;
import com.apishield.security.keysharding.domain.RecoveryResult;
import com.apishield.security.keysharding.domain.ShardSecret;
import com.apishield.security.keysharding.spi.ShamirCryptoEngine;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class DefaultShamirCryptoEngine implements ShamirCryptoEngine {

    public static final BigInteger PRIME = BigInteger.valueOf(2).pow(256).subtract(BigInteger.valueOf(189));
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public ShardSecret generateShares(String secret, int threshold, int totalShares) {
        BigInteger secretBigInt = new BigInteger(secret.getBytes(StandardCharsets.UTF_8));
        if (secretBigInt.compareTo(PRIME) >= 0) {
            throw new IllegalArgumentException("Secret too large for prime field");
        }

        int[] coefficients = new int[threshold];
        coefficients[0] = secretBigInt.intValue();
        for (int i = 1; i < threshold; i++) {
            coefficients[i] = new BigInteger(256, SECURE_RANDOM).mod(PRIME).intValue();
        }

        String keyId = UUID.randomUUID().toString();
        List<KeyShare> shares = new ArrayList<>();

        for (int i = 1; i <= totalShares; i++) {
            String shareValue = generateShareValue(i, coefficients, PRIME.intValue());
            KeyShare share = KeyShare.builder()
                    .shareId(UUID.randomUUID().toString())
                    .keyId(keyId)
                    .shareIndex(i)
                    .shareValue(shareValue)
                    .createdAt(LocalDateTime.now())
                    .active(true)
                    .build();
            shares.add(share);
        }

        return ShardSecret.builder()
                .keyId(keyId)
                .originalSecretHash(hashSecret(secret))
                .threshold(threshold)
                .totalShares(totalShares)
                .shares(shares)
                .algorithm("Shamir-SSS-256")
                .build();
    }

    @Override
    public RecoveryResult recoverSecret(String keyId, Map<Integer, String> shares, int threshold) {
        if (shares.size() < threshold) {
            return RecoveryResult.builder()
                    .success(false)
                    .keyId(keyId)
                    .usedShares(shares.size())
                    .threshold(threshold)
                    .message("Insufficient shares: need " + threshold + ", got " + shares.size())
                    .build();
        }

        try {
            BigInteger[] xValues = new BigInteger[shares.size()];
            BigInteger[] yValues = new BigInteger[shares.size()];

            int i = 0;
            for (Map.Entry<Integer, String> entry : shares.entrySet()) {
                xValues[i] = BigInteger.valueOf(entry.getKey());
                yValues[i] = new BigInteger(entry.getValue());
                i++;
            }

            BigInteger secret = lagrangeInterpolation(BigInteger.ZERO, xValues, yValues);
            String recoveredSecret = new String(secret.toByteArray(), StandardCharsets.UTF_8);

            return RecoveryResult.builder()
                    .success(true)
                    .recoveredSecret(recoveredSecret)
                    .keyId(keyId)
                    .usedShares(shares.size())
                    .threshold(threshold)
                    .message("Secret recovered successfully")
                    .build();
        } catch (Exception e) {
            return RecoveryResult.builder()
                    .success(false)
                    .keyId(keyId)
                    .usedShares(shares.size())
                    .threshold(threshold)
                    .message("Recovery failed: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public String generateShareValue(int x, int[] coefficients, int prime) {
        BigInteger result = evaluatePolynomial(BigInteger.valueOf(x), coefficients, BigInteger.valueOf(prime));
        return result.toString();
    }

    private BigInteger evaluatePolynomial(BigInteger x, int[] coefficients, BigInteger prime) {
        BigInteger result = BigInteger.ZERO;
        BigInteger power = BigInteger.ONE;

        for (int coeff : coefficients) {
            result = result.add(power.multiply(BigInteger.valueOf(coeff))).mod(prime);
            power = power.multiply(x).mod(prime);
        }

        return result;
    }

    @Override
    public int modInverse(int a, int m) {
        BigInteger result = BigInteger.valueOf(a).modInverse(BigInteger.valueOf(m));
        return result.intValue();
    }

    private BigInteger lagrangeInterpolation(BigInteger x, BigInteger[] xValues, BigInteger[] yValues) {
        BigInteger result = BigInteger.ZERO;
        int n = xValues.length;

        for (int i = 0; i < n; i++) {
            BigInteger numerator = BigInteger.ONE;
            BigInteger denominator = BigInteger.ONE;

            for (int j = 0; j < n; j++) {
                if (i != j) {
                    numerator = numerator.multiply(x.subtract(xValues[j])).mod(PRIME);
                    denominator = denominator.multiply(xValues[i].subtract(xValues[j])).mod(PRIME);
                }
            }

            BigInteger lagrangeTerm = yValues[i]
                    .multiply(numerator)
                    .multiply(denominator.modInverse(PRIME))
                    .mod(PRIME);

            result = result.add(lagrangeTerm).mod(PRIME);
        }

        return result;
    }

    private String hashSecret(String secret) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash secret", e);
        }
    }
}
