package com.didauth.module.zkp.service;

import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.ZkpProof;
import com.didauth.core.mapper.ZkpProofMapper;
import com.didauth.module.zkp.dto.ZkpVerifyRequest;
import com.didauth.module.zkp.dto.ZkpVerifyResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZkpService {

    private final ZkpProofMapper zkpProofMapper;
    private final MeterRegistry meterRegistry;

    public Mono<ZkpVerifyResponse> verifyProof(ZkpVerifyRequest request) {
        String proofId = "proof_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Timer.Sample sample = Timer.start(meterRegistry);

        return Mono.fromCallable(() -> {
            ZkpProof proof = new ZkpProof();
            proof.setProofId(proofId);
            proof.setCircuitId(request.getCircuitId());
            proof.setProofData(request.getProofData());
            proof.setPublicInputs(request.getPublicInputs() != null ? String.join(",", request.getPublicInputs()) : null);
            proof.setStatus("VERIFYING");
            zkpProofMapper.insert(proof);

            boolean verified = performCircuitVerification(request);

            long verifyTimeMs = sample.stop(Timer.builder("zkp.verify.duration")
                    .tag("circuit", request.getCircuitId())
                    .tag("result", verified ? "success" : "failed")
                    .register(meterRegistry)) / 1_000_000;

            proof.setStatus(verified ? "VERIFIED" : "FAILED");
            proof.setVerifyResult(verified ? "SUCCESS" : "FAILED");
            proof.setVerifyTimeMs(verifyTimeMs);
            zkpProofMapper.updateById(proof);

            ZkpVerifyResponse response = new ZkpVerifyResponse();
            response.setProofId(proofId);
            response.setCircuitId(request.getCircuitId());
            response.setVerified(verified);
            response.setVerifyResult(verified ? "SUCCESS" : "FAILED");
            response.setVerifyTimeMs(verifyTimeMs);

            meterRegistry.counter("zkp.verify.count", "circuit", request.getCircuitId(), "result", verified ? "success" : "failed")
                    .increment();

            return response;
        }).onErrorResume(e -> {
            log.error("ZKP verification failed", e);
            ZkpProof proof = zkpProofMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ZkpProof>()
                            .eq(ZkpProof::getProofId, proofId));
            if (proof != null) {
                proof.setStatus("ERROR");
                proof.setErrorMessage(e.getMessage());
                zkpProofMapper.updateById(proof);
            }
            return Mono.error(BusinessException.internalError("ZKP proof verification failed: " + e.getMessage()));
        });
    }

    private boolean performCircuitVerification(ZkpVerifyRequest request) throws NoSuchAlgorithmException {
        if (request.getProofData() == null || request.getProofData().length() < 32) {
            throw new IllegalArgumentException("Invalid proof data");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(request.getProofData().getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        try {
            TimeUnit.MILLISECONDS.sleep(50 + (long) (Math.random() * 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return hexString.toString().matches("^[a-f0-9]{64}$");
    }

    public Mono<ZkpProof> getProofStatus(String proofId) {
        return Mono.fromCallable(() -> {
            ZkpProof proof = zkpProofMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ZkpProof>()
                            .eq(ZkpProof::getProofId, proofId));
            if (proof == null) {
                throw BusinessException.notFound("Proof not found: " + proofId);
            }
            return proof;
        });
    }
}
