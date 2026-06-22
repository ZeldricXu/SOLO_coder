package com.enterprise.gateway.transform.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JsonXmlConverterTest {

    private JsonXmlConverter converter;
    private NettyDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        converter = new JsonXmlConverter();
        bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);
    }

    @Test
    void shouldSupportJsonToXml() {
        assertThat(converter.supports(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML)).isTrue();
    }

    @Test
    void shouldSupportXmlToJson() {
        assertThat(converter.supports(MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON)).isTrue();
    }

    @Test
    void shouldConvertJsonToXml() {
        String json = "{\"name\":\"test\",\"value\":123}";
        DataBuffer inputBuffer = bufferFactory.allocateBuffer(json.length());
        inputBuffer.write(json.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = converter.convert(inputBuffer, MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    assertThat(xml).contains("<name>test</name>");
                    assertThat(xml).contains("<value>123</value>");
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldConvertXmlToJson() {
        String xml = "<root><name>test</name><value>123</value></root>";
        DataBuffer inputBuffer = bufferFactory.allocateBuffer(xml.length());
        inputBuffer.write(xml.getBytes(StandardCharsets.UTF_8));

        Mono<DataBuffer> result = converter.convert(inputBuffer, MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON);

        StepVerifier.create(result)
                .assertNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    String json = new String(bytes, StandardCharsets.UTF_8);
                    assertThat(json).contains("\"name\":\"test\"");
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                })
                .verifyComplete();
    }

    @Test
    void shouldNotConvertUnsupportedTypes() {
        assertThat(converter.supports(MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)).isFalse();
    }
}
