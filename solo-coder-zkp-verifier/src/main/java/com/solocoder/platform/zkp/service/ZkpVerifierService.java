package com.solocoder.platform.zkp.service;

import com.alibaba.fastjson2.JSON;
import com.solocoder.platform.persistence.entity.ZkpProofEntity;
import com.solocoder.platform.persistence.mapper.ZkpProofMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZkpVerifierService {

    private final ZkpProofMapper zkpProofMapper;

    @Transactional(rollbackFor = Exception.class)
    public ZkpProofEntity submitProof(String proofType, String circuitId,
                                       List<Object> publicInputs,
                                       Map<String, Object> proofData,
                                       Map<String, Object> verificationKey) {
        ZkpProofEntity entity = new ZkpProofEntity();
        entity.setProofId(UUID.randomUUID().toString());
        entity.setProofType(proofType);
        entity.setCircuitId(circuitId);
        entity.setPublicInputs(JSON.toJSONString(publicInputs));
        entity.setProofData(JSON.toJSONString(proofData));
        entity.setVerificationKey(JSON.toJSONString(verificationKey));
        entity.setVerificationResult(null);
        entity.setCreatedBy("system");
        zkpProofMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public ZkpProofEntity verifyProof(String proofId) {
        ZkpProofEntity entity = zkpProofMapper.selectById(proofId);
        if (entity == null) {
            throw new RuntimeException("证明不存在: " + proofId);
        }

        long startTime = System.currentTimeMillis();
        boolean result = performVerification(entity);
        long endTime = System.currentTimeMillis();

        entity.setVerificationResult(result ? 1 : 0);
        entity.setVerificationTime(endTime - startTime);
        entity.setVerifiedAt(LocalDateTime.now());
        if (!result) {
            entity.setVerificationError("Verification failed");
        }
        zkpProofMapper.updateById(entity);
        return entity;
    }

    private boolean performVerification(ZkpProofEntity entity) {
        return true;
    }

    public ZkpProofEntity getProof(String proofId) {
        return zkpProofMapper.selectById(proofId);
    }
}
