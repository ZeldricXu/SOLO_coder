package com.enterprise.gateway.transform.wrapper;

import com.enterprise.gateway.common.model.UnifiedResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class UnifiedResponseWrapperTest {

    private UnifiedResponseWrapper wrapper;
    private NettyDataBufferFactory bufferFactory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        wrapper = new UnifiedResponseWrapper();
        bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldWrapSuccessResponse() {
        String body = "{\"userId\":123,\"username\":\"testuser\"}";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(body.length());
        bodyBuffer.write(body.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = wrapper.wrap(bodyBuffer, HttpStatus.OK);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    UnifiedResponse<Map<String, Object>> response = objectMapper.readValue(
                            json, new TypeReference<UnifiedResponse<Map<String, Object>>>() {}
                    );
                    assertThat(response.getCode()).isEqualTo(200);
                    assertThat(response.getMessage()).isEqualTo("success");
                    assertThat(response.getData()).isNotNull();
                    assertThat(response.getTimestamp()).isNotNull();
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldWrapErrorResponse() {
        String body = "{\"message\":\"Internal Server Error\"}";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(body.length());
        bodyBuffer.write(body.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = wrapper.wrap(bodyBuffer, HttpStatus.INTERNAL_SERVER_ERROR);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    UnifiedResponse<Object> response = objectMapper.readValue(
                            json, new TypeReference<UnifiedResponse<Object>>() {}
                    );
                    assertThat(response.getCode()).isEqualTo(500);
                    assertThat(response.getMessage()).isEqualTo("Internal Server Error");
                    assertThat(response.getTimestamp()).isNotNull();
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldWrapNotFoundResponse() {
        String body = "Resource not found";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(body.length());
        bodyBuffer.write(body.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = wrapper.wrap(bodyBuffer, HttpStatus.NOT_FOUND);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    UnifiedResponse<Object> response = objectMapper.readValue(
                            json, new TypeReference<UnifiedResponse<Object>>() {}
                    );
                    assertThat(response.getCode()).isEqualTo(404);
                    assertThat(response.getMessage()).isEqualTo("Resource not found");
                    assertThat(response.getTimestamp()).isNotNull();
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldPreserveDataInResponse() {
        String body = "{\"itemId\":456,\"itemName\":\"Test Item\",\"price\":99.99}";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(body.length());
        bodyBuffer.write(body.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = wrapper.wrap(bodyBuffer, HttpStatus.OK);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    UnifiedResponse<Map<String, Object>> response = objectMapper.readValue(
                            json, new TypeReference<UnifiedResponse<Map<String, Object>>>() {}
                    );
                    Map<String, Object> data = response.getData();
                    assertThat(data).isNotNull();
                    assertThat(data.get("itemId")).isEqualTo(456);
                    assertThat(data.get("itemName")).isEqualTo("Test Item");
                    assertThat(data.get("price")).isEqualTo(99.99);
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldNotLeakInternalServerInfo() {
        String body = "{\"stackTrace\":\"com.enterprise.service.ServiceException: Error\\n\\tat com.enterprise.Service.process(Service.java:42)\\n\",\"serviceUrl\":\"http://internal-service:8080/api\"}";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(body.length());
        bodyBuffer.write(body.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = wrapper.wrap(bodyBuffer, HttpStatus.INTERNAL_SERVER_ERROR);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    assertThat(json).doesNotContain("stackTrace");
                    assertThat(json).doesNotContain("at com.enterprise");
                    assertThat(json).doesNotContain("internal-service");
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }
}
