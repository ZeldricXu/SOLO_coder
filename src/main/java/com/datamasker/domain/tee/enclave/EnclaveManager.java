package com.datamasker.domain.tee.enclave;

import com.datamasker.domain.tee.model.EnclaveInstance;
import com.datamasker.infrastructure.crypto.CryptoUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EnclaveManager {

    private final ConcurrentHashMap<String, EnclaveInstance> enclaveCache = new ConcurrentHashMap<>();

    public EnclaveInstance createEnclave() {
        String enclaveId = UUID.randomUUID().toString();
        try {
            String measurementHash = CryptoUtils.sha256Hash(enclaveId + LocalDateTime.now().toString());
            EnclaveInstance instance = new EnclaveInstance();
            instance.setEnclaveId(enclaveId);
            instance.setStatus("CREATED");
            instance.setMeasurementHash(measurementHash);
            instance.setCreatedAt(LocalDateTime.now());
            instance.setUpdatedAt(LocalDateTime.now());
            enclaveCache.put(enclaveId, instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create enclave", e);
        }
    }

    public EnclaveInstance startEnclave(String enclaveId) {
        EnclaveInstance instance = getEnclave(enclaveId);
        if (instance == null) {
            throw new RuntimeException("Enclave not found: " + enclaveId);
        }
        instance.setStatus("RUNNING");
        instance.setUpdatedAt(LocalDateTime.now());
        enclaveCache.put(enclaveId, instance);
        return instance;
    }

    public EnclaveInstance stopEnclave(String enclaveId) {
        EnclaveInstance instance = getEnclave(enclaveId);
        if (instance == null) {
            throw new RuntimeException("Enclave not found: " + enclaveId);
        }
        instance.setStatus("STOPPED");
        instance.setUpdatedAt(LocalDateTime.now());
        enclaveCache.put(enclaveId, instance);
        return instance;
    }

    public EnclaveInstance getEnclave(String enclaveId) {
        return enclaveCache.get(enclaveId);
    }
}
