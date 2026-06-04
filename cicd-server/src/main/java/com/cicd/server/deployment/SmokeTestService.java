package com.cicd.server.deployment;

import com.cicd.common.dto.pipeline.SmokeTestConfig;
import com.cicd.common.dto.pipeline.SmokeTestEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmokeTestService {

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public boolean runSmokeTests(String baseUrl, SmokeTestConfig config) {
        int timeout = config.getTimeout() > 0 ? config.getTimeout() : 60;
        int retries = config.getRetries() > 0 ? config.getRetries() : 3;
        long deadline = System.currentTimeMillis() + timeout * 1000L;

        for (SmokeTestEndpoint endpoint : config.getEndpoints()) {
            boolean passed = testEndpointWithRetry(baseUrl, endpoint, retries, deadline);
            if (!passed) {
                log.error("Smoke test failed for endpoint: {}", endpoint.getPath());
                return false;
            }
        }

        log.info("All smoke tests passed for {}", baseUrl);
        return true;
    }

    private boolean testEndpointWithRetry(String baseUrl, SmokeTestEndpoint endpoint, int retries, long deadline) {
        int attempt = 0;
        while (attempt < retries && System.currentTimeMillis() < deadline) {
            try {
                if (testEndpoint(baseUrl, endpoint)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Smoke test attempt {} failed for {}: {}", attempt + 1, endpoint.getPath(), e.getMessage());
            }
            attempt++;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean testEndpoint(String baseUrl, SmokeTestEndpoint endpoint) throws Exception {
        String url = (baseUrl.startsWith("http") ? baseUrl : "https://" + baseUrl) + endpoint.getPath();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .method(endpoint.getMethod() != null ? endpoint.getMethod() : "GET", HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(10));

        if (endpoint.getHeaders() != null) {
            for (String header : endpoint.getHeaders().split(",")) {
                String[] parts = header.split(":", 2);
                if (parts.length == 2) {
                    builder.header(parts[0].trim(), parts[1].trim());
                }
            }
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        int expectedStatus = endpoint.getExpectedStatus() > 0 ? endpoint.getExpectedStatus() : 200;
        if (response.statusCode() != expectedStatus) {
            log.warn("Endpoint {} returned status {}, expected {}", endpoint.getPath(), response.statusCode(), expectedStatus);
            return false;
        }

        if (endpoint.getExpectedBody() != null && !endpoint.getExpectedBody().isEmpty()) {
            if (!response.body().contains(endpoint.getExpectedBody())) {
                log.warn("Endpoint {} response does not contain expected body", endpoint.getPath());
                return false;
            }
        }

        log.info("Smoke test passed for {}", endpoint.getPath());
        return true;
    }
}
