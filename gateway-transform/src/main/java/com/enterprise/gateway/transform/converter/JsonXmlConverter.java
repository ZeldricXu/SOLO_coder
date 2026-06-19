package com.enterprise.gateway.transform.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

@Slf4j
@Component
public class JsonXmlConverter implements PayloadConverter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XmlMapper xmlMapper = new XmlMapper();
    private final DataBufferFactory bufferFactory = new NettyDataBufferFactory(io.netty.buffer.PooledByteBufAllocator.DEFAULT);

    @Override
    public Mono<DataBuffer> convert(DataBuffer input, MediaType from, MediaType to) {
        return Mono.fromCallable(() -> {
            try {
                InputStream inputStream = input.asInputStream();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                byte[] inputBytes = outputStream.toByteArray();
                DataBufferUtils.release(input);

                byte[] outputBytes;
                if (MediaType.APPLICATION_JSON.isCompatibleWith(from) && MediaType.APPLICATION_XML.isCompatibleWith(to)) {
                    outputBytes = jsonToXml(inputBytes);
                } else if (MediaType.APPLICATION_XML.isCompatibleWith(from) && MediaType.APPLICATION_JSON.isCompatibleWith(to)) {
                    outputBytes = xmlToJson(inputBytes);
                } else {
                    throw new IllegalArgumentException("Unsupported conversion: " + from + " -> " + to);
                }

                DataBuffer outputBuffer = bufferFactory.allocateBuffer(outputBytes.length);
                outputBuffer.write(outputBytes);
                return outputBuffer;
            } catch (Exception e) {
                log.error("Failed to convert payload", e);
                DataBufferUtils.release(input);
                throw new RuntimeException("Payload conversion failed", e);
            }
        });
    }

    private byte[] jsonToXml(byte[] jsonBytes) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(jsonBytes);
        String xml = xmlMapper.writer().withRootName("root").writeValueAsString(jsonNode);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xmlToJson(byte[] xmlBytes) throws Exception {
        JsonNode jsonNode = xmlMapper.readTree(new ByteArrayInputStream(xmlBytes));
        return objectMapper.writeValueAsBytes(jsonNode);
    }

    @Override
    public boolean supports(MediaType from, MediaType to) {
        return (MediaType.APPLICATION_JSON.isCompatibleWith(from) && MediaType.APPLICATION_XML.isCompatibleWith(to)) ||
               (MediaType.APPLICATION_XML.isCompatibleWith(from) && MediaType.APPLICATION_JSON.isCompatibleWith(to));
    }
}
