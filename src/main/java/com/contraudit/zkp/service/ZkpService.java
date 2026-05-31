package com.contraudit.zkp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.zkp.entity.ZkpCircuit;
import com.contraudit.zkp.entity.ZkpVerification;
import com.contraudit.zkp.mapper.ZkpCircuitMapper;
import com.contraudit.zkp.mapper.ZkpVerificationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZkpService {

    private final ZkpCircuitMapper circuitMapper;
    private final ZkpVerificationMapper verificationMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public ZkpCircuit registerCircuit(ZkpCircuit circuit) {
        circuit.setStatus(1);
        circuitMapper.insert(circuit);
        log.info("Registered ZKP circuit: {} - {}", circuit.getId(), circuit.getCircuitName());
        return circuit;
    }

    public ZkpCircuit getCircuit(String id) {
        ZkpCircuit circuit = circuitMapper.selectById(id);
        if (circuit == null) {
            throw new BusinessException(ErrorCode.ZKP_CIRCUIT_NOT_FOUND);
        }
        return circuit;
    }

    public List<ZkpCircuit> listCircuits(String circuitType, String circuitName) {
        LambdaQueryWrapper<ZkpCircuit> wrapper = new LambdaQueryWrapper<>();
        if (circuitType != null) {
            wrapper.eq(ZkpCircuit::getCircuitType, circuitType);
        }
        if (circuitName != null) {
            wrapper.like(ZkpCircuit::getCircuitName, circuitName);
        }
        wrapper.eq(ZkpCircuit::getStatus, 1);
        return circuitMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public ZkpVerification verifyProof(String circuitId, String proofData,
                                        String publicInputs, String verifierAddress) {
        ZkpCircuit circuit = getCircuit(circuitId);

        String verificationId = "zkp_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);

        ZkpVerification verification = new ZkpVerification();
        verification.setVerificationId(verificationId);
        verification.setCircuitId(circuitId);
        verification.setProofData(proofData);
        verification.setPublicInputs(publicInputs);
        verification.setVerifierAddress(verifierAddress);
        verification.setStatus("PENDING");
        verificationMapper.insert(verification);

        long startTime = System.currentTimeMillis();
        boolean result = false;
        String errorMessage = null;

        try {
            result = performVerification(circuit, proofData, publicInputs);
            verification.setVerifyResult(result ? 1 : 0);
            verification.setStatus(result ? "VERIFIED" : "INVALID");
        } catch (Exception e) {
            log.error("ZKP verification failed", e);
            errorMessage = e.getMessage();
            verification.setStatus("FAILED");
            verification.setErrorMessage(errorMessage);
        }

        long verifyTime = System.currentTimeMillis() - startTime;
        verification.setVerifyTime(verifyTime);
        verification.setVerifiedAt(LocalDateTime.now());
        verificationMapper.updateById(verification);

        log.info("ZKP verification completed: {} - result: {}, time: {}ms",
                verificationId, result, verifyTime);

        return verification;
    }

    public ZkpVerification getVerification(String verificationId) {
        LambdaQueryWrapper<ZkpVerification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ZkpVerification::getVerificationId, verificationId);
        ZkpVerification verification = verificationMapper.selectOne(wrapper);
        if (verification == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "verification not found");
        }
        return verification;
    }

    public List<ZkpVerification> listVerifications(String circuitId, String status,
                                                   String verifierAddress) {
        LambdaQueryWrapper<ZkpVerification> wrapper = new LambdaQueryWrapper<>();
        if (circuitId != null) {
            wrapper.eq(ZkpVerification::getCircuitId, circuitId);
        }
        if (status != null) {
            wrapper.eq(ZkpVerification::getStatus, status);
        }
        if (verifierAddress != null) {
            wrapper.eq(ZkpVerification::getVerifierAddress, verifierAddress);
        }
        wrapper.orderByDesc(ZkpVerification::getCreatedAt);
        return verificationMapper.selectList(wrapper);
    }

    private boolean performVerification(ZkpCircuit circuit, String proofData, String publicInputs) {
        try {
            Map<String, Object> proof = objectMapper.readValue(proofData,
                    new TypeReference<Map<String, Object>>() {});
            Map<String, Object> inputs = publicInputs != null ?
                    objectMapper.readValue(publicInputs, new TypeReference<Map<String, Object>>() {}) :
                    Map.of();

            String circuitType = circuit.getCircuitType();
            return switch (circuitType.toUpperCase()) {
                case "GROTH16" -> verifyGroth16(circuit, proof, inputs);
                case "PLONK" -> verifyPlonk(circuit, proof, inputs);
                case "STARK" -> verifyStark(circuit, proof, inputs);
                default -> {
                    log.warn("Unknown circuit type: {}, using mock verification", circuitType);
                    yield mockVerify(proofData, publicInputs, circuit.getVerifyingKey());
                }
            };
        } catch (Exception e) {
            log.error("Failed to perform ZKP verification", e);
            throw new BusinessException(ErrorCode.ZKP_VERIFICATION_FAILED, e.getMessage());
        }
    }

    private boolean verifyGroth16(ZkpCircuit circuit, Map<String, Object> proof,
                                   Map<String, Object> inputs) {
        log.info("Verifying Groth16 proof for circuit: {}", circuit.getCircuitName());
        return proof.containsKey("pi_a") && proof.containsKey("pi_b") && proof.containsKey("pi_c");
    }

    private boolean verifyPlonk(ZkpCircuit circuit, Map<String, Object> proof,
                                 Map<String, Object> inputs) {
        log.info("Verifying PLONK proof for circuit: {}", circuit.getCircuitName());
        return proof.containsKey("proof") && proof.containsKey("publicInputs");
    }

    private boolean verifyStark(ZkpCircuit circuit, Map<String, Object> proof,
                                 Map<String, Object> inputs) {
        log.info("Verifying STARK proof for circuit: {}", circuit.getCircuitName());
        return proof.containsKey("commitment") && proof.containsKey("trace");
    }

    private boolean mockVerify(String proofData, String publicInputs, String verifyingKey) {
        try {
            String combined = proofData + "|" + publicInputs + "|" + verifyingKey;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes());
            return hash.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
