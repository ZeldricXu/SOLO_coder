package com.scheduler.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolConverter {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public Mono<String> convertAndForward(String targetUrl, Object body, String sourceProtocol, String targetProtocol) {
        Object convertedBody = convertBody(body, sourceProtocol, targetProtocol);
        MediaType mediaType = getMediaType(targetProtocol);

        return webClientBuilder.build()
                .post()
                .uri(targetUrl)
                .contentType(mediaType)
                .body(BodyInserters.fromValue(convertedBody))
                .retrieve()
                .bodyToMono(String.class)
                .doOnError(e -> log.error("Failed to forward request to {}", targetUrl, e));
    }

    private Object convertBody(Object body, String sourceProtocol, String targetProtocol) {
        try {
            if ("JSON".equalsIgnoreCase(targetProtocol)) {
                if (body instanceof String) {
                    return body;
                }
                return objectMapper.writeValueAsString(body);
            }
            if ("FORM".equalsIgnoreCase(targetProtocol)) {
                if (body instanceof Map) {
                    return body;
                }
                if (body instanceof String) {
                    return objectMapper.readValue((String) body, Map.class);
                }
            }
        } catch (Exception e) {
            log.warn("Protocol conversion failed, using original body", e);
        }
        return body;
    }

    private MediaType getMediaType(String protocol) {
        return switch (protocol.toUpperCase()) {
            case "JSON" -> MediaType.APPLICATION_JSON;
            case "XML" -> MediaType.APPLICATION_XML;
            case "FORM" -> MediaType.APPLICATION_FORM_URLENCODED;
            case "TEXT" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_JSON;
        };
    }

    public Map<String, Object> parseBody(String body, String contentType) {
        try {
            if (contentType != null && contentType.contains("json")) {
                return objectMapper.readValue(body, Map.class);
            }
        } catch (Exception e) {
            log.debug("Failed to parse body", e);
        }
        return Map.of("raw", body);
    }
}
