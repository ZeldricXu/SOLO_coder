package com.datamasker.domain.shamir.algorithm;

import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.domain.shamir.model.SecretRecoveryResult;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class ShamirSecretSharing {

    private final SecureRandom random = new SecureRandom();

    public List<KeyShard> split(BigInteger secret, int threshold, int totalShares, BigInteger prime) {
        BigInteger[] coefficients = new BigInteger[threshold];
        coefficients[0] = secret;
        for (int i = 1; i < threshold; i++) {
            coefficients[i] = new BigInteger(prime.bitLength(), random).mod(prime.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        }

        List<KeyShard> shards = new ArrayList<>();
        for (int x = 1; x <= totalShares; x++) {
            BigInteger xVal = BigInteger.valueOf(x);
            BigInteger y = evaluatePolynomial(coefficients, xVal, prime);
            shards.add(new KeyShard(null, x, y, threshold, totalShares, null));
        }
        return shards;
    }

    public SecretRecoveryResult reconstruct(List<KeyShard> shards, BigInteger prime) {
        BigInteger secret = BigInteger.ZERO;
        int k = shards.size();

        for (int i = 0; i < k; i++) {
            BigInteger xi = BigInteger.valueOf(shards.get(i).getShardIndex());
            BigInteger yi = shards.get(i).getShardData();
            BigInteger numerator = BigInteger.ONE;
            BigInteger denominator = BigInteger.ONE;

            for (int j = 0; j < k; j++) {
                if (i == j) continue;
                BigInteger xj = BigInteger.valueOf(shards.get(j).getShardIndex());
                numerator = numerator.multiply(BigInteger.ZERO.subtract(xj)).mod(prime);
                denominator = denominator.multiply(xi.subtract(xj)).mod(prime);
            }

            BigInteger lagrangeCoeff = numerator.multiply(modInverse(denominator, prime)).mod(prime);
            secret = secret.add(yi.multiply(lagrangeCoeff)).mod(prime);
        }

        return new SecretRecoveryResult(null, secret, LocalDateTime.now(), k);
    }

    public BigInteger generatePrime(int bits) {
        return BigInteger.probablePrime(bits, random);
    }

    private BigInteger evaluatePolynomial(BigInteger[] coefficients, BigInteger x, BigInteger prime) {
        BigInteger result = BigInteger.ZERO;
        for (int j = coefficients.length - 1; j >= 0; j--) {
            result = result.multiply(x).add(coefficients[j]).mod(prime);
        }
        return result;
    }

    private BigInteger modInverse(BigInteger a, BigInteger m) {
        return a.modInverse(m);
    }

    private BigInteger modPow(BigInteger base, BigInteger exponent, BigInteger m) {
        return base.modPow(exponent, m);
    }
}
