package com.enterprise.gateway.transform.filter;

import com.enterprise.gateway.common.model.UnifiedResponse;
import com.enterprise.gateway.transform.wrapper.UnifiedResponseWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ResponseWrapperFilterTest {

    private ResponseWrapperFilter filter;
    private NettyDataBufferFactory bufferFactory;
    private ObjectMapper objectMapper;

    @Mock
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        UnifiedResponseWrapper responseWrapper = new UnifiedResponseWrapper();
        filter = new ResponseWrapperFilter(responseWrapper);
        bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);
        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldReturnCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(-10);
    }

    @Test
    void shouldWrapDownstreamResponse() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerHttpResponse response = new MockServerHttpResponse();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                .response(response)
                .build();

        String plainJson = "{\"userId\":123,\"username\":\"testuser\"}";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(plainJson.length());
        bodyBuffer.write(plainJson.getBytes(StandardCharsets.UTF_8));

        exchange.getResponse().setStatusCode(HttpStatus.OK);

        StepVerifier.create(filter.filter(exchange, chain)
                        .then(Mono.defer(() -> exchange.getResponse().writeWith(Mono.just(bodyBuffer)))))
                .verifyComplete();

        StepVerifier.create(response.getBody())
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    UnifiedResponse<Map<String, Object>> unifiedResponse = objectMapper.readValue(
                            json, new TypeReference<UnifiedResponse<Map<String, Object>>>() {}
                    );
                    assertThat(unifiedResponse.getCode()).isEqualTo(200);
                    assertThat(unifiedResponse.getMessage()).isEqualTo("success");
                    assertThat(unifiedResponse.getData()).isNotNull();
                    Map<String, Object> data = unifiedResponse.getData();
                    assertThat(data.get("userId")).isEqualTo(123);
                    assertThat(data.get("username")).isEqualTo("testuser");
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldSetJsonContentType() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerHttpResponse response = new MockServerHttpResponse();
        MockServerWebExchange exchange = MockServerWebExchange.builder(request)
                .response(response)
                .build();

        String plainJson = "{\"result\":\"ok\"}";
        DataBuffer bodyBuffer = bufferFactory.allocateBuffer(plainJson.length());
        bodyBuffer.write(plainJson.getBytes(StandardCharsets.UTF_8));

        exchange.getResponse().setStatusCode(HttpStatus.OK);

        StepVerifier.create(filter.filter(exchange, chain)
                        .then(Mono.defer(() -> exchange.getResponse().writeWith(Mono.just(bodyBuffer)))))
                .verifyComplete();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }
}
