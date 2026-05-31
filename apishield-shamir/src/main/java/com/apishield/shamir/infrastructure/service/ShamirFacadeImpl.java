package com.apishield.shamir.infrastructure.service;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.shamir.api.ShamirFacade;
import com.apishield.shamir.domain.model.ShamirKeyShare;
import com.apishield.shamir.domain.repository.ShareRepository;
import com.apishield.shamir.domain.service.ShamirCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShamirFacadeImpl implements ShamirFacade {

    private final ShamirCryptoService cryptoService;
    private final ShareRepository shareRepository;

    @Override
    public List<ShamirKeyShare> generateShares(String secret, int threshold, int totalShares) {
        return generateShares(secret, threshold, totalShares, IdGenerator.generateId("key"));
    }

    @Override
    public List<ShamirKeyShare> generateShares(String secret, int threshold, int totalShares, String keyId) {
        if (threshold > totalShares) {
            throw new BusinessException("SHAMIR_001", "阈值不能大于总分片数");
        }

        BigInteger secretInt = new BigInteger(secret.getBytes());
        if (secretInt.compareTo(cryptoService.getPrime()) >= 0) {
            throw new BusinessException("SHAMIR_001", "密钥值过大");
        }

        BigInteger[] coefficients = cryptoService.generateCoefficients(secretInt, threshold);

        List<ShamirKeyShare> shares = IntStream.rangeClosed(1, totalShares)
                .mapToObj(i -> {
                    BigInteger x = BigInteger.valueOf(i);
                    BigInteger y = cryptoService.evaluatePolynomial(coefficients, x);

                    ShamirKeyShare share = new ShamirKeyShare();
                    share.setId(IdGenerator.generateId("share"));
                    share.setKeyId(keyId);
                    share.setShareIndex(i);
                    share.setShareValue(y.toString());
                    share.setThreshold(threshold);
                    share.setTotalShares(totalShares);
                    share.setStatus(ShamirKeyShare.ShareStatus.GENERATED);
                    share.setCreatedAt(LocalDateTime.now());
                    share.setUpdatedAt(LocalDateTime.now());

                    return shareRepository.save(share);
                })
                .collect(Collectors.toList());

        log.info("Generated {} shares for key {}, threshold: {}", totalShares, keyId, threshold);
        return shares;
    }

    @Override
    public String recoverSecret(List<ShamirKeyShare> shares) {
        if (shares.isEmpty()) {
            throw new BusinessException("SHAMIR_003", "分片列表为空");
        }

        int threshold = shares.get(0).getThreshold();
        if (shares.size() < threshold) {
            throw new BusinessException("SHAMIR_003", 
                String.format("需要至少%d个分片，当前只有%d个", threshold, shares.size()));
        }

        BigInteger[] xPoints = shares.stream()
                .map(s -> BigInteger.valueOf(s.getShareIndex()))
                .toArray(BigInteger[]::new);
        BigInteger[] yPoints = shares.stream()
                .map(s -> new BigInteger(s.getShareValue()))
                .toArray(BigInteger[]::new);

        BigInteger secret = cryptoService.lagrangeInterpolation(xPoints, yPoints, BigInteger.ZERO);
        return new String(secret.toByteArray());
    }

    @Override
    public String recoverSecret(Map<Integer, String> shareValues, int threshold) {
        if (shareValues.size() < threshold) {
            throw new BusinessException("SHAMIR_003", 
                String.format("需要至少%d个分片，当前只有%d个", threshold, shareValues.size()));
        }

        BigInteger[] xPoints = shareValues.keySet().stream()
                .map(BigInteger::valueOf)
                .toArray(BigInteger[]::new);
        BigInteger[] yPoints = shareValues.values().stream()
                .map(BigInteger::new)
                .toArray(BigInteger[]::new);

        BigInteger secret = cryptoService.lagrangeInterpolation(xPoints, yPoints, BigInteger.ZERO);
        return new String(secret.toByteArray());
    }

    @Override
    public Optional<ShamirKeyShare> findById(String shareId) {
        return shareRepository.findById(shareId);
    }

    @Override
    public List<ShamirKeyShare> findByKeyId(String keyId) {
        return shareRepository.findByKeyId(keyId);
    }

    @Override
    public List<ShamirKeyShare> findByOwnerId(String ownerId) {
        return shareRepository.findByOwnerId(ownerId);
    }

    @Override
    public void distributeShare(String shareId, String ownerId) {
        ShamirKeyShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "分片不存在: " + shareId));
        share.setOwnerId(ownerId);
        share.setStatus(ShamirKeyShare.ShareStatus.DISTRIBUTED);
        share.setUpdatedAt(LocalDateTime.now());
        shareRepository.save(share);
        log.info("Distributed share {} to owner {}", shareId, ownerId);
    }

    @Override
    public void revokeShare(String shareId) {
        ShamirKeyShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "分片不存在: " + shareId));
        share.setStatus(ShamirKeyShare.ShareStatus.REVOKED);
        share.setUpdatedAt(LocalDateTime.now());
        shareRepository.save(share);
        log.info("Revoked share {}", shareId);
    }

    @Override
    public ShamirKeyShare updateShareStatus(String shareId, ShamirKeyShare.ShareStatus status) {
        ShamirKeyShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "分片不存在: " + shareId));
        share.setStatus(status);
        share.setUpdatedAt(LocalDateTime.now());
        return shareRepository.save(share);
    }
}
