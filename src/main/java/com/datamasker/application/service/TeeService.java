package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datamasker.domain.tee.enclave.AttestationService;
import com.datamasker.domain.tee.enclave.EnclaveManager;
import com.datamasker.domain.tee.model.AttestationResult;
import com.datamasker.domain.tee.model.EnclaveInstance;
import com.datamasker.domain.tee.model.SecureChannel;
import com.datamasker.infrastructure.persistence.entity.TeeEnclaveEntity;
import com.datamasker.infrastructure.persistence.mapper.TeeEnclaveMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TeeService {

    private final EnclaveManager enclaveManager;
    private final AttestationService attestationService;
    private final TeeEnclaveMapper teeEnclaveMapper;

    public EnclaveInstance createAndStartEnclave() {
        EnclaveInstance instance = enclaveManager.createEnclave();
        enclaveManager.startEnclave(instance.getEnclaveId());

        TeeEnclaveEntity entity = new TeeEnclaveEntity();
        entity.setEnclaveId(instance.getEnclaveId());
        entity.setStatus("RUNNING");
        entity.setMeasurementHash(instance.getMeasurementHash());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        teeEnclaveMapper.insert(entity);

        return enclaveManager.getEnclave(instance.getEnclaveId());
    }

    public AttestationResult attestEnclave(String enclaveId, String expectedMeasurement) {
        AttestationResult result = attestationService.performAttestation(enclaveId, expectedMeasurement);

        TeeEnclaveEntity entity = findEntityByEnclaveId(enclaveId);
        if (entity != null) {
            entity.setStatus(result.isVerified() ? "ATTESTED" : "ERROR");
            entity.setAttestationReport(result.getReportBody());
            entity.setUpdatedAt(LocalDateTime.now());
            teeEnclaveMapper.updateById(entity);
        }

        return result;
    }

    public TeeEnclaveEntity getEnclaveStatus(String enclaveId) {
        return findEntityByEnclaveId(enclaveId);
    }

    public SecureChannel establishChannel(String enclaveId) {
        return attestationService.establishSecureChannel(enclaveId);
    }

    public void stopEnclave(String enclaveId) {
        enclaveManager.stopEnclave(enclaveId);

        TeeEnclaveEntity entity = findEntityByEnclaveId(enclaveId);
        if (entity != null) {
            entity.setStatus("STOPPED");
            entity.setUpdatedAt(LocalDateTime.now());
            teeEnclaveMapper.updateById(entity);
        }
    }

    private TeeEnclaveEntity findEntityByEnclaveId(String enclaveId) {
        LambdaQueryWrapper<TeeEnclaveEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TeeEnclaveEntity::getEnclaveId, enclaveId);
        return teeEnclaveMapper.selectOne(wrapper);
    }
}
