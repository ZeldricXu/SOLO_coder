package com.enterprise.gateway.transform.spi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ProtocolTranscoderRegistry {

    private final Map<String, GrpcToRestTranscoder> transcoders = new ConcurrentHashMap<>();

    public void register(GrpcToRestTranscoder transcoder) {
        String key = transcoder.getClass().getName();
        transcoders.put(key, transcoder);
        log.info("Registered GrpcToRestTranscoder: {}", key);
    }

    public Optional<GrpcToRestTranscoder> getTranscoder(String serviceName, String methodName) {
        return transcoders.values().stream()
                .filter(transcoder -> transcoder.supports(serviceName, methodName))
                .findFirst();
    }

    public boolean unregister(String transcoderClassName) {
        GrpcToRestTranscoder removed = transcoders.remove(transcoderClassName);
        if (removed != null) {
            log.info("Unregistered GrpcToRestTranscoder: {}", transcoderClassName);
            return true;
        }
        return false;
    }
}
