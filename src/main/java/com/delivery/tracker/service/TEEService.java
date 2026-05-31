package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.TeeEnclave;
import com.delivery.tracker.mapper.TeeEnclaveMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TEEService {

    private final TeeEnclaveMapper teeEnclaveMapper;
    private final Map<String, byte[]> enclaveSecrets = new ConcurrentHashMap<>();

    public Mono<TeeEnclave> createEnclave() {
        return Mono.fromCallable(() -> {
            String enclaveId = "enclave_" + UUID.randomUUID().toString().substring(0, 8);

            KeyPair keyPair = generateKeyPair();
            String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            enclaveSecrets.put(enclaveId, keyPair.getPrivate().getEncoded());

            TeeEnclave enclave = new TeeEnclave();
            enclave.setEnclaveId(enclaveId);
            enclave.setStatus("INITIALIZING");
            enclave.setPublicKey(publicKey);
            enclave.setLastHealthCheck(LocalDateTime.now());
            teeEnclaveMapper.insert(enclave);

            enclave.setStatus("RUNNING");
            enclave.setAttestationReport(generateAttestationReport(enclaveId, publicKey));
            teeEnclaveMapper.updateById(enclave);

            log.info("TEE Enclave创建成功: enclaveId={}", enclaveId);
            return enclave;
        });
    }

    public Mono<TeeEnclave> getEnclave(String enclaveId) {
        return Mono.fromCallable(() ->
                teeEnclaveMapper.selectOne(
                        new LambdaQueryWrapper<TeeEnclave>()
                                .eq(TeeEnclave::getEnclaveId, enclaveId)
                )
        );
    }

    public Flux<TeeEnclave> getAllEnclaves() {
        return Flux.fromIterable(teeEnclaveMapper.selectList(null));
    }

    public Mono<Boolean> verifyAttestation(String enclaveId, String challenge) {
        return getEnclave(enclaveId)
                .switchIfEmpty(Mono.error(new RuntimeException("Enclave不存在: " + enclaveId)))
                .map(enclave -> {
                    String expectedReport = generateAttestationReport(enclaveId, enclave.getPublicKey());
                    boolean valid = enclave.getAttestationReport() != null
                            && enclave.getAttestationReport().equals(expectedReport);
                    log.info("远程证明验证结果: enclaveId={}, valid={}", enclaveId, valid);
                    return valid;
                });
    }

    public Mono<String> encryptData(String enclaveId, String plaintext) {
        return getEnclave(enclaveId)
                .switchIfEmpty(Mono.error(new RuntimeException("Enclave不存在: " + enclaveId)))
                .map(enclave -> {
                    byte[] encrypted = enclaveEncrypt(enclaveId, plaintext.getBytes());
                    return Base64.getEncoder().encodeToString(encrypted);
                });
    }

    public Mono<String> decryptData(String enclaveId, String ciphertext) {
        return getEnclave(enclaveId)
                .switchIfEmpty(Mono.error(new RuntimeException("Enclave不存在: " + enclaveId)))
                .map(enclave -> {
                    byte[] decrypted = enclaveDecrypt(enclaveId, Base64.getDecoder().decode(ciphertext));
                    return new String(decrypted);
                });
    }

    public Mono<TeeEnclave> healthCheck(String enclaveId) {
        return getEnclave(enclaveId)
                .switchIfEmpty(Mono.error(new RuntimeException("Enclave不存在: " + enclaveId)))
                .doOnNext(enclave -> {
                    enclave.setLastHealthCheck(LocalDateTime.now());
                    enclave.setStatus("RUNNING");
                    teeEnclaveMapper.updateById(enclave);
                    log.debug("Enclave健康检查成功: enclaveId={}", enclaveId);
                });
    }

    public Mono<Void> destroyEnclave(String enclaveId) {
        return Mono.fromRunnable(() -> {
            TeeEnclave enclave = teeEnclaveMapper.selectOne(
                    new LambdaQueryWrapper<TeeEnclave>()
                            .eq(TeeEnclave::getEnclaveId, enclaveId)
            );
            if (enclave != null) {
                enclave.setStatus("DESTROYED");
                teeEnclaveMapper.updateById(enclave);
                enclaveSecrets.remove(enclaveId);
                log.info("Enclave已销毁: enclaveId={}", enclaveId);
            }
        });
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            return keyGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA算法不可用", e);
        }
    }

    private String generateAttestationReport(String enclaveId, String publicKey) {
        String reportData = enclaveId + "|" + publicKey + "|" + LocalDateTime.now();
        return Base64.getEncoder().encodeToString(reportData.getBytes());
    }

    private byte[] enclaveEncrypt(String enclaveId, byte[] data) {
        byte[] secret = enclaveSecrets.get(enclaveId);
        if (secret == null) {
            throw new RuntimeException("Enclave密钥不存在");
        }
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ secret[i % secret.length]);
        }
        return result;
    }

    private byte[] enclaveDecrypt(String enclaveId, byte[] data) {
        return enclaveEncrypt(enclaveId, data);
    }
}
