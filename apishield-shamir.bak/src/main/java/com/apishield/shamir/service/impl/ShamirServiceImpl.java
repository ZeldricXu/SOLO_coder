package com.apishield.shamir.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.shamir.domain.ShamirKeyShare;
import com.apishield.shamir.dto.ShamirGenerateRequest;
import com.apishield.shamir.dto.ShamirRecoverRequest;
import com.apishield.shamir.service.ShamirService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class ShamirServiceImpl implements ShamirService {

    private static final BigInteger PRIME = new BigInteger("208351617316091241234326746312124448251235562226470491514186331217050270460481");
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ShamirKeyShare> shareStore = new HashMap<>();

    @Override
    public List<ShamirKeyShare> generateShares(ShamirGenerateRequest request) {
        if (request.getThreshold() > request.getTotalShares()) {
            throw new BusinessException("SHAMIR_001", "阈值不能大于总分片数");
        }

        BigInteger secret = new BigInteger(request.getSecret().getBytes());
        if (secret.compareTo(PRIME) >= 0) {
            throw new BusinessException("SHAMIR_001", "密钥值过大");
        }

        BigInteger[] coefficients = new BigInteger[request.getThreshold()];
        coefficients[0] = secret;
        for (int i = 1; i < request.getThreshold(); i++) {
            coefficients[i] = new BigInteger(PRIME.bitLength(), random).mod(PRIME);
        }

        List<ShamirKeyShare> shares = IntStream.rangeClosed(1, request.getTotalShares())
                .mapToObj(i -> {
                    BigInteger x = BigInteger.valueOf(i);
                    BigInteger y = evaluatePolynomial(coefficients, x);

                    ShamirKeyShare share = new ShamirKeyShare();
                    share.setId(IdGenerator.generateId("share"));
                    share.setKeyId(request.getKeyId() != null ? request.getKeyId() : IdGenerator.generateId("key"));
                    share.setShareIndex(i);
                    share.setShareValue(y.toString());
                    share.setThreshold(request.getThreshold());
                    share.setTotalShares(request.getTotalShares());
                    share.setStatus("GENERATED");
                    share.setCreatedAt(LocalDateTime.now());
                    share.setUpdatedAt(LocalDateTime.now());

                    shareStore.put(share.getId(), share);
                    return share;
                })
                .collect(Collectors.toList());

        log.info("Generated {} shares for key {}, threshold: {}", 
                request.getTotalShares(), request.getKeyId(), request.getThreshold());
        return shares;
    }

    @Override
    public String recoverSecret(ShamirRecoverRequest request) {
        Map<Integer, String> shares = request.getShares();
        if (shares.size() < request.getThreshold()) {
            throw new BusinessException("SHAMIR_003", 
                String.format("需要至少%d个分片，当前只有%d个", request.getThreshold(), shares.size()));
        }

        BigInteger[] xPoints = shares.keySet().stream()
                .map(BigInteger::valueOf)
                .toArray(BigInteger[]::new);
        BigInteger[] yPoints = shares.values().stream()
                .map(BigInteger::new)
                .toArray(BigInteger[]::new);

        BigInteger secret = lagrangeInterpolation(xPoints, yPoints, BigInteger.ZERO);
        return new String(secret.toByteArray());
    }

    @Override
    public ShamirKeyShare getShareById(String id) {
        ShamirKeyShare share = shareStore.get(id);
        if (share == null) {
            throw new BusinessException("NOT_FOUND", "分片不存在: " + id);
        }
        return share;
    }

    @Override
    public List<ShamirKeyShare> getSharesByKeyId(String keyId) {
        return shareStore.values().stream()
                .filter(s -> keyId.equals(s.getKeyId()))
                .collect(Collectors.toList());
    }

    @Override
    public void distributeShare(String shareId, String ownerId) {
        ShamirKeyShare share = getShareById(shareId);
        share.setOwnerId(ownerId);
        share.setStatus("DISTRIBUTED");
        share.setUpdatedAt(LocalDateTime.now());
        log.info("Distributed share {} to owner {}", shareId, ownerId);
    }

    private BigInteger evaluatePolynomial(BigInteger[] coefficients, BigInteger x) {
        BigInteger result = BigInteger.ZERO;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = result.multiply(x).add(coefficients[i]).mod(PRIME);
        }
        return result;
    }

    private BigInteger lagrangeInterpolation(BigInteger[] x, BigInteger[] y, BigInteger targetX) {
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
}
