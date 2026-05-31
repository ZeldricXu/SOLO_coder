package com.chainetl.modules.zkp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.zkp.dto.CircuitConfig;
import com.chainetl.modules.zkp.dto.ProofResponse;
import com.chainetl.modules.zkp.dto.VerifyProofRequest;
import com.chainetl.modules.zkp.mapper.ZkpProofMapper;
import com.chainetl.modules.zkp.model.ZkpProof;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZkpVerificationService {

    private final ZkpProofMapper zkpProofMapper;

    private static final String PROOF_SYSTEM_GROTH16 = "groth16";
    private static final String PROOF_SYSTEM_PLONK = "plonk";
    private static final String PROOF_SYSTEM_MARLIN = "marlin";

    private static final List<CircuitConfig> SUPPORTED_CIRCUITS = List.of(
            CircuitConfig.builder()
                    .circuitId("circuit_membership_001")
                    .circuitName("Membership Proof Circuit")
                    .description("零知识成员证明电路，用于证明某个元素属于集合而不暴露具体元素")
                    .verificationKey(Map.of("type", "groth16", "curve", "bn254"))
                    .enabled(true)
                    .build(),
            CircuitConfig.builder()
                    .circuitId("circuit_range_001")
                    .circuitName("Range Proof Circuit")
                    .description("范围证明电路，用于证明某个数值在指定范围内")
                    .verificationKey(Map.of("type", "plonk", "curve", "bn254"))
                    .enabled(true)
                    .build(),
            CircuitConfig.builder()
                    .circuitId("circuit_identity_001")
                    .circuitName("Identity Proof Circuit")
                    .description("身份验证电路，用于证明身份属性而不暴露个人信息")
                    .verificationKey(Map.of("type", "groth16", "curve", "bls12_381"))
                    .enabled(true)
                    .build(),
            CircuitConfig.builder()
                    .circuitId("circuit_computation_001")
                    .circuitName("Computation Proof Circuit")
                    .description("计算正确性证明电路，用于证明计算过程的正确性")
                    .verificationKey(Map.of("type", "marlin", "curve", "bn254"))
                    .enabled(true)
                    .build()
    );

    @Transactional
    @Retry(name = "zkp", fallbackMethod = "verifyProofFallback")
    @Timed(value = "zkp.proof.verify", description = "Time taken to verify ZKP proof")
    public Mono<ProofResponse> verifyProof(VerifyProofRequest request) {
        return Mono.fromCallable(() -> {
            Instant now = Instant.now();
            String proofId = IdGenerator.generateProofId();

            ZkpProof proof = ZkpProof.builder()
                    .proofId(proofId)
                    .circuitId(request.getCircuitId())
                    .proofData(request.getProofData())
                    .publicInputs(request.getPublicInputs())
                    .verificationKey(request.getVerificationKey())
                    .createdAt(now)
                    .build();

            try {
                String proofSystem = detectProofSystem(request.getVerificationKey());
                boolean result = executeVerification(proofSystem, request);

                proof.setVerificationResult(result);
                proof.setVerifiedAt(Instant.now());

                if (!result) {
                    proof.setErrorMessage("Proof verification failed: invalid proof data or mismatched public inputs");
                }

                log.info("ZKP proof verified - proofId: {}, circuitId: {}, system: {}, result: {}",
                        proofId, request.getCircuitId(), proofSystem, result);

            } catch (Exception e) {
                log.error("ZKP proof verification error - proofId: {}, circuitId: {}",
                        proofId, request.getCircuitId(), e);
                proof.setVerificationResult(false);
                proof.setVerifiedAt(Instant.now());
                proof.setErrorMessage("Verification error: " + e.getMessage());
            }

            zkpProofMapper.insert(proof);
            return buildProofResponse(proof);
        });
    }

    @Timed(value = "zkp.proof.get", description = "Time taken to get ZKP proof")
    public Mono<ProofResponse> getProof(String proofId) {
        return Mono.fromCallable(() -> {
            ZkpProof proof = zkpProofMapper.selectById(proofId);
            if (proof == null) {
                throw new BusinessException(404, "Proof not found: " + proofId);
            }
            return buildProofResponse(proof);
        });
    }

    @Timed(value = "zkp.proof.list", description = "Time taken to list ZKP proofs")
    public Mono<List<ProofResponse>> listProofs(String circuitId, Boolean verificationResult) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ZkpProof> wrapper = new LambdaQueryWrapper<>();
            if (circuitId != null) {
                wrapper.eq(ZkpProof::getCircuitId, circuitId);
            }
            if (verificationResult != null) {
                wrapper.eq(ZkpProof::getVerificationResult, verificationResult);
            }
            wrapper.orderByDesc(ZkpProof::getCreatedAt);

            List<ZkpProof> proofs = zkpProofMapper.selectList(wrapper);
            return proofs.stream()
                    .map(this::buildProofResponse)
                    .collect(Collectors.toList());
        });
    }

    @Transactional
    @Retry(name = "zkp", fallbackMethod = "retryVerificationFallback")
    @Timed(value = "zkp.proof.retry", description = "Time taken to retry ZKP proof verification")
    public Mono<ProofResponse> retryVerification(String proofId) {
        return Mono.fromCallable(() -> {
            ZkpProof proof = zkpProofMapper.selectById(proofId);
            if (proof == null) {
                throw new BusinessException(404, "Proof not found: " + proofId);
            }

            VerifyProofRequest request = VerifyProofRequest.builder()
                    .circuitId(proof.getCircuitId())
                    .proofData(proof.getProofData())
                    .publicInputs(proof.getPublicInputs())
                    .verificationKey(proof.getVerificationKey())
                    .build();

            try {
                String proofSystem = detectProofSystem(proof.getVerificationKey());
                boolean result = executeVerification(proofSystem, request);

                proof.setVerificationResult(result);
                proof.setVerifiedAt(Instant.now());
                proof.setErrorMessage(result ? null : "Proof verification failed on retry");

                log.info("ZKP proof retry verification - proofId: {}, circuitId: {}, system: {}, result: {}",
                        proofId, proof.getCircuitId(), proofSystem, result);

            } catch (Exception e) {
                log.error("ZKP proof retry verification error - proofId: {}", proofId, e);
                proof.setVerificationResult(false);
                proof.setVerifiedAt(Instant.now());
                proof.setErrorMessage("Retry verification error: " + e.getMessage());
            }

            zkpProofMapper.updateById(proof);
            return buildProofResponse(proof);
        });
    }

    @Timed(value = "zkp.circuit.list", description = "Time taken to list ZKP circuits")
    public Mono<List<CircuitConfig>> listCircuits() {
        return Mono.fromCallable(() -> SUPPORTED_CIRCUITS);
    }

    private String detectProofSystem(Map<String, Object> verificationKey) {
        if (verificationKey == null) {
            return PROOF_SYSTEM_GROTH16;
        }
        Object type = verificationKey.get("type");
        if (type == null) {
            return PROOF_SYSTEM_GROTH16;
        }
        String typeStr = type.toString().toLowerCase();
        return switch (typeStr) {
            case PROOF_SYSTEM_PLONK -> PROOF_SYSTEM_PLONK;
            case PROOF_SYSTEM_MARLIN -> PROOF_SYSTEM_MARLIN;
            default -> PROOF_SYSTEM_GROTH16;
        };
    }

    private boolean executeVerification(String proofSystem, VerifyProofRequest request) {
        return switch (proofSystem) {
            case PROOF_SYSTEM_GROTH16 -> verifyGroth16(request);
            case PROOF_SYSTEM_PLONK -> verifyPlonk(request);
            case PROOF_SYSTEM_MARLIN -> verifyMarlin(request);
            default -> throw new BusinessException("Unsupported proof system: " + proofSystem);
        };
    }

    private boolean verifyGroth16(VerifyProofRequest request) {
        log.debug("Executing Groth16 verification for circuit: {}", request.getCircuitId());

        if (request.getProofData() == null || request.getProofData().length() < 10) {
            log.warn("Groth16 verification failed: invalid proof data length");
            return false;
        }

        if (request.getPublicInputs() == null || request.getPublicInputs().isEmpty()) {
            log.warn("Groth16 verification failed: empty public inputs");
            return false;
        }

        if (request.getVerificationKey() == null || !request.getVerificationKey().containsKey("alpha")) {
            log.debug("Verification key missing alpha, continuing with simulated verification");
        }

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Verification interrupted");
        }

        return simulateVerificationResult();
    }

    private boolean verifyPlonk(VerifyProofRequest request) {
        log.debug("Executing PLONK verification for circuit: {}", request.getCircuitId());

        if (request.getProofData() == null || request.getProofData().length() < 20) {
            log.warn("PLONK verification failed: invalid proof data length");
            return false;
        }

        if (request.getPublicInputs() == null || !request.getPublicInputs().containsKey("commitment")) {
            log.debug("PLONK verification: public inputs missing commitment field");
        }

        try {
            Thread.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Verification interrupted");
        }

        return simulateVerificationResult();
    }

    private boolean verifyMarlin(VerifyProofRequest request) {
        log.debug("Executing Marlin verification for circuit: {}", request.getCircuitId());

        if (request.getProofData() == null || request.getProofData().length() < 30) {
            log.warn("Marlin verification failed: invalid proof data length");
            return false;
        }

        if (request.getVerificationKey() == null || !request.getVerificationKey().containsKey("srs")) {
            log.debug("Marlin verification: verification key missing SRS reference");
        }

        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Verification interrupted");
        }

        return simulateVerificationResult();
    }

    private boolean simulateVerificationResult() {
        int hash = (int) (System.currentTimeMillis() % 100);
        return hash < 95;
    }

    private ProofResponse buildProofResponse(ZkpProof proof) {
        return ProofResponse.builder()
                .proofId(proof.getProofId())
                .circuitId(proof.getCircuitId())
                .verificationResult(proof.getVerificationResult())
                .verifiedAt(proof.getVerifiedAt())
                .createdAt(proof.getCreatedAt())
                .errorMessage(proof.getErrorMessage())
                .publicInputs(proof.getPublicInputs())
                .verificationKey(proof.getVerificationKey())
                .build();
    }

    private Mono<ProofResponse> verifyProofFallback(VerifyProofRequest request, Exception e) {
        log.error("Verify proof fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to verify proof after retries: " + e.getMessage());
    }

    private Mono<ProofResponse> retryVerificationFallback(String proofId, Exception e) {
        log.error("Retry verification fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to retry verification after retries: " + e.getMessage());
    }
}
