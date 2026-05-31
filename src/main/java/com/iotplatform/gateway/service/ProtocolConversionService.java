package com.iotplatform.gateway.service;

import com.iotplatform.gateway.dto.ProtocolConvertRequest;
import reactor.core.publisher.Mono;

public interface ProtocolConversionService {

    Mono<String> convert(ProtocolConvertRequest request);

    Mono<byte[]> convertToBinary(ProtocolConvertRequest request);

    Mono<String> convertFromBinary(String sourceProtocol, byte[] payload);

    Mono<Boolean> supportsProtocol(String protocol);

    Flux<String> getSupportedProtocols();
}
