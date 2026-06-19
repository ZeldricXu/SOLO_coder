package com.enterprise.gateway.transform.wrapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.enterprise.gateway.common.model.UnifiedResponse;
import com.enterprise.gateway.common.util.JacksonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

@Slf4j
@Component
public class UnifiedResponseWrapper {

    private final DataBufferFactory bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);

    public Mono<DataBuffer> wrap(DataBuffer body, HttpStatus status) {
        return Mono.fromCallable(() -> {
            try {
                byte[] bodyBytes = new byte[body.readableByteCount()];
                body.read(bodyBytes);
                org.springframework.core.io.buffer.DataBufferUtils.release(body);

                String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
                UnifiedResponse<Object> unifiedResponse;

                if (status.is2xxSuccessful()) {
                    Object data;
                    try {
                        data = JacksonUtil.fromJson(bodyStr, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        data = bodyStr;
                    }
                    unifiedResponse = UnifiedResponse.success(data);
                    unifiedResponse.setCode(status.value());
                } else {
                    String message;
                    try {
                        Map<String, Object> errorMap = JacksonUtil.fromJson(bodyStr, new TypeReference<Map<String, Object>>() {});
                        message = (String) errorMap.getOrDefault("message", status.getReasonPhrase());
                    } catch (Exception e) {
                        message = bodyStr.isEmpty() ? status.getReasonPhrase() : bodyStr;
                    }
                    unifiedResponse = UnifiedResponse.error(status.value(), message);
                }

                byte[] responseBytes = JacksonUtil.toJson(unifiedResponse).getBytes(StandardCharsets.UTF_8);
                DataBuffer outputBuffer = bufferFactory.allocateBuffer(responseBytes.length);
                outputBuffer.write(responseBytes);
                return outputBuffer;
            } catch (Exception e) {
                log.error("Failed to wrap response", e);
                org.springframework.core.io.buffer.DataBufferUtils.release(body);
                throw new RuntimeException("Response wrapping failed", e);
            }
        });
    }
}
