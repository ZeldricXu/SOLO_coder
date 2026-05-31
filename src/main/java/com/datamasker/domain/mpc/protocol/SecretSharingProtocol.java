package com.datamasker.domain.mpc.protocol;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Component
public class SecretSharingProtocol {

    private final SecureRandom random = new SecureRandom();

    public List<BigInteger> splitInput(BigInteger value, int partyCount, BigInteger modulus) {
        List<BigInteger> shares = new ArrayList<>();
        BigInteger sum = BigInteger.ZERO;

        for (int i = 0; i < partyCount - 1; i++) {
            BigInteger share = new BigInteger(modulus.bitLength(), random).mod(modulus);
            shares.add(share);
            sum = sum.add(share);
        }

        BigInteger lastShare = value.subtract(sum).mod(modulus);
        shares.add(lastShare);

        return shares;
    }

    public BigInteger reconstructResult(List<BigInteger> shares, BigInteger modulus) {
        BigInteger sum = BigInteger.ZERO;
        for (BigInteger share : shares) {
            sum = sum.add(share);
        }
        return sum.mod(modulus);
    }

    public List<BigInteger> computeOnShares(List<List<BigInteger>> partyShares, BigInteger modulus) {
        if (partyShares == null || partyShares.isEmpty()) {
            return new ArrayList<>();
        }

        int shareCount = partyShares.get(0).size();
        List<BigInteger> result = new ArrayList<>();

        for (int i = 0; i < shareCount; i++) {
            BigInteger sum = BigInteger.ZERO;
            for (List<BigInteger> shares : partyShares) {
                sum = sum.add(shares.get(i));
            }
            result.add(sum.mod(modulus));
        }

        return result;
    }
}
