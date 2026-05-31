package com.iotplatform.gateway.service.impl;

import com.iotplatform.common.constant.ErrorCodeConstants;
import com.iotplatform.common.constant.MetricConstants;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.gateway.dto.ProtocolConvertRequest;
import com.iotplatform.gateway.enums.ProtocolType;
import com.iotplatform.gateway.service.ProtocolConversionService;
import com.iotplatform.gateway.strategy.ProtocolConverterFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolConversionServiceImpl implements ProtocolConversionService {

    private final ProtocolConverterFactory converterFactory;
    private final MeterRegistry meterRegistry;

    @Override
    public Mono<String> convert(ProtocolConvertRequest request) {
        return timedOperation(MetricConstants.GATEWAY_PROTOCOL_CONVERT_LATENCY, () ->
                Mono.fromCallable(() -> {
                    validateRequest(request);
                    String result = converterFactory.convert(request);
                    log.debug("Protocol converted from {} to {}", request.getSourceProtocol(), request.getTargetProtocol());
                    meterRegistry.counter(MetricConstants.GATEWAY_PROTOCOL_CONVERT_SUCCESS).increment();
                    return result;
                })
                .doOnError(e -> {
                    log.error("Protocol conversion failed: {}", e.getMessage());
                    meterRegistry.counter(MetricConstants.GATEWAY_PROTOCOL_CONVERT_FAILURE).increment();
                })
                .onErrorMap(e -> new BusinessException(ErrorCodeConstants.PROTOCOL_CONVERSION_FAILED,
                        "协议转换失败: " + e.getMessage()))
        );
    }

    @Override
    public Mono<byte[]> convertToBinary(ProtocolConvertRequest request) {
        return convert(request)
                .map(result -> result.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<String> convertFromBinary(String sourceProtocol, byte[] payload) {
        return Mono.fromCallable(() -> {
            validateProtocol(sourceProtocol);
            return new String(payload, StandardCharsets.UTF_8);
        });
    }

    @Override
    public Mono<Boolean> supportsProtocol(String protocol) {
        return Mono.just(ProtocolType.isSupported(protocol));
    }

    @Override
    public Flux<String> getSupportedProtocols() {
        return Flux.fromIterable(ProtocolType.getSupportedProtocols());
    }

    private void validateRequest(ProtocolConvertRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCodeConstants.BAD_REQUEST, "转换请求不能为空");
        }
        validateProtocol(request.getSourceProtocol());
        validateProtocol(request.getTargetProtocol());
        if (request.getPayload() == null) {
            throw new BusinessException(ErrorCodeConstants.PROTOCOL_INVALID_PAYLOAD, "转换数据不能为空");
        }
    }

    private void validateProtocol(String protocol) {
        if (!ProtocolType.isSupported(protocol)) {
            throw new BusinessException(ErrorCodeConstants.PROTOCOL_NOT_SUPPORTED, "不支持的协议: " + protocol);
        }
    }

    private <T> Mono<T> timedOperation(String metricName, Supplier<Mono<T>> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return operation.get()
                .doFinally(s -> sample.stop(meterRegistry.timer(metricName)));
    }
}
