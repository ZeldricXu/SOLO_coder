package com.chain.infrastructure.zkverifier.service;

import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.persistence.entity.ZkProof;
import com.chain.infrastructure.persistence.mapper.ZkProofMapper;
import com.chain.infrastructure.zkverifier.dto.ZkProofRequest;
import com.chain.infrastructure.zkverifier.dto.ZkProofResult;
import com.chain.infrastructure.zkverifier.verifier.ZkVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZkProofService {

    private final Map<String, ZkVerifier> verifiers;
    private final ZkProofMapper zkProofMapper;

    public ZkProofService(List<ZkVerifier> verifierList, ZkProofMapper zkProofMapper) {
        this.verifiers = verifierList.stream()
                .collect(Collectors.toMap(v -> v.getSchemeType().toUpperCase(), Function.identity()));
        this.zkProofMapper = zkProofMapper;
    }

    public Mono<ZkProofResult> verifyProof(ZkProofRequest request) {
        long startTime = System.currentTimeMillis();

        ZkVerifier verifier = getVerifier(request.getSchemeType());

        return verifier.verify(request)
                .flatMap(verified -> {
                    long elapsed = System.currentTimeMillis() - startTime;

                    String proofId = IdGenerator.generateId("zkp");

                    ZkProof proof = new ZkProof();
                    proof.setProofId(proofId);
                    proof.setCircuitId(request.getCircuitId());
                    proof.setSchemeType(request.getSchemeType());
                    proof.setProofData(request.getProofData());
                    proof.setPublicInputs(JsonUtils.toJson(request.getPublicInputs()));
                    proof.setVerificationKey(request.getVerificationKey());
                    proof.setVerified(verified);
                    proof.setVerifiedAt(LocalDateTime.now());
                    proof.setVerificationResult(verified ? "VERIFIED" : "INVALID_PROOF");
                    zkProofMapper.insert(proof);

                    ZkProofResult result = new ZkProofResult();
                    result.setProofId(proofId);
                    result.setCircuitId(request.getCircuitId());
                    result.setSchemeType(request.getSchemeType());
                    result.setVerified(verified);
                    result.setVerificationResult(verified ? "VERIFIED" : "INVALID_PROOF");
                    result.setVerificationTimeMs(elapsed);
                    result.setVerifiedAt(LocalDateTime.now());

                    log.info("ZKP verification completed: proofId={}, scheme={}, verified={}, time={}ms",
                            proofId, request.getSchemeType(), verified, elapsed);

                    return Mono.just(result);
                });
    }

    public Mono<ZkProof> getProof(String proofId) {
        return Mono.justOrEmpty(zkProofMapper.selectById(proofId));
    }

    public Mono<ZkProofResult> verifyProofBatch(List<ZkProofRequest> requests) {
        return Flux.fromIterable(requests)
                .flatMap(this::verifyProof)
                .collectList()
                .map(results -> {
                    boolean allVerified = results.stream().allMatch(ZkProofResult::getVerified);
                    ZkProofResult batchResult = new ZkProofResult();
                    batchResult.setProofId("batch_" + IdGenerator.generateHash(JsonUtils.toJson(requests)));
                    batchResult.setVerified(allVerified);
                    batchResult.setVerificationResult(allVerified ? "ALL_VERIFIED" : "SOME_FAILED");
                    batchResult.setVerifiedAt(LocalDateTime.now());
                    return batchResult;
                });
    }

    private ZkVerifier getVerifier(String schemeType) {
        ZkVerifier verifier = verifiers.get(schemeType.toUpperCase());
        if (verifier == null) {
            throw new IllegalArgumentException("Unsupported ZKP scheme: " + schemeType);
        }
        return verifier;
    }
}
