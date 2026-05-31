package com.datapipeline.tee.enclave;

import lombok.extern.slf4j.Slf4j;

import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class EnclaveManager {

    public enum EnclaveType {
        SGX,
        SEV,
        TDX,
        SIMULATED
    }

    private final Map<String, EnclaveInstance> enclaves = new ConcurrentHashMap<>();
    private final EnclaveType defaultType;
    private final SecureRandom secureRandom;

    public EnclaveManager() {
        this(EnclaveType.SIMULATED);
    }

    public EnclaveManager(EnclaveType defaultType) {
        this.defaultType = defaultType;
        this.secureRandom = new SecureRandom();
    }

    public EnclaveInstance createEnclave(String name, long memorySize) {
        return createEnclave(name, memorySize, defaultType);
    }

    public EnclaveInstance createEnclave(String name, long memorySize, EnclaveType type) {
        String enclaveId = UUID.randomUUID().toString();

        KeyPair keyPair = generateKeyPair();
        byte[] mrenclave = new byte[32];
        byte[] mrsigner = new byte[32];
        secureRandom.nextBytes(mrenclave);
        secureRandom.nextBytes(mrsigner);

        EnclaveInstance enclave = EnclaveInstance.builder()
                .enclaveId(enclaveId)
                .enclaveType(type.name())
                .status(EnclaveInstance.Status.CREATED)
                .publicKey(keyPair.getPublic())
                .mrenclave(mrenclave)
                .mrsigner(mrsigner)
                .isvProdId(1)
                .isvSvn(1)
                .createdAt(Instant.now())
                .memorySize(memorySize)
                .threadCount(1)
                .metadata(Map.of("name", name))
                .build();

        enclaves.put(enclaveId, enclave);
        log.info("Enclave created: id={}, type={}, memory={}", enclaveId, type, memorySize);
        return enclave;
    }

    public boolean initializeEnclave(String enclaveId) {
        EnclaveInstance enclave = enclaves.get(enclaveId);
        if (enclave == null) {
            log.warn("Enclave not found: id={}", enclaveId);
            return false;
        }

        enclave.setStatus(EnclaveInstance.Status.INITIALIZED);
        enclave.setStartedAt(Instant.now());
        log.info("Enclave initialized: id={}", enclaveId);
        return true;
    }

    public boolean startEnclave(String enclaveId) {
        EnclaveInstance enclave = enclaves.get(enclaveId);
        if (enclave == null) {
            log.warn("Enclave not found: id={}", enclaveId);
            return false;
        }

        if (enclave.getStatus() == EnclaveInstance.Status.CREATED) {
            initializeEnclave(enclaveId);
        }

        enclave.setStatus(EnclaveInstance.Status.RUNNING);
        log.info("Enclave started: id={}", enclaveId);
        return true;
    }

    public boolean pauseEnclave(String enclaveId) {
        EnclaveInstance enclave = enclaves.get(enclaveId);
        if (enclave == null) {
            log.warn("Enclave not found: id={}", enclaveId);
            return false;
        }

        enclave.setStatus(EnclaveInstance.Status.PAUSED);
        log.info("Enclave paused: id={}", enclaveId);
        return true;
    }

    public boolean terminateEnclave(String enclaveId) {
        EnclaveInstance enclave = enclaves.get(enclaveId);
        if (enclave == null) {
            log.warn("Enclave not found: id={}", enclaveId);
            return false;
        }

        enclave.setStatus(EnclaveInstance.Status.TERMINATED);
        enclave.setTerminatedAt(Instant.now());
        log.info("Enclave terminated: id={}", enclaveId);
        return true;
    }

    public Optional<EnclaveInstance> getEnclave(String enclaveId) {
        return Optional.ofNullable(enclaves.get(enclaveId));
    }

    public List<EnclaveInstance> getAllEnclaves() {
        return new ArrayList<>(enclaves.values());
    }

    public List<EnclaveInstance> getEnclavesByStatus(EnclaveInstance.Status status) {
        return enclaves.values().stream()
                .filter(e -> e.getStatus() == status)
                .collect(java.util.stream.Collectors.toList());
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(3072, secureRandom);
            return keyGen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate enclave key pair", e);
        }
    }

}
