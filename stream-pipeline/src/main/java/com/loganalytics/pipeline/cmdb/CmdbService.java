package com.loganalytics.pipeline.cmdb;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.loganalytics.common.model.ServiceMetadata;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.pipeline.config.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class CmdbService {
    private static final Logger log = LoggerFactory.getLogger(CmdbService.class);

    private final PipelineConfig config;
    private final Cache<String, ServiceMetadata> metadataCache;
    private final HttpClient httpClient;

    public CmdbService(PipelineConfig config) {
        this.config = config;
        this.metadataCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(config.getCmdbCacheTtlMinutes()))
                .maximumSize(10000)
                .build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ServiceMetadata getServiceMetadata(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return null;
        }

        try {
            return metadataCache.get(serviceName, this::fetchFromCmdb);
        } catch (Exception e) {
            log.debug("Failed to get CMDB metadata for service: {}", serviceName, e);
            return buildDefaultMetadata(serviceName);
        }
    }

    private ServiceMetadata fetchFromCmdb(String serviceName) {
        try {
            String url = config.getCmdbServiceUrl() + "/api/services/" + serviceName;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                return JsonUtils.fromJson(response.body(), ServiceMetadata.class);
            }
        } catch (Exception e) {
            log.debug("CMDB fetch failed for {}, using default", serviceName, e);
        }
        return buildDefaultMetadata(serviceName);
    }

    private ServiceMetadata buildDefaultMetadata(String serviceName) {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName(serviceName);
        metadata.setTeamName("unknown");
        metadata.setEnvironment("production");

        Map<String, String> labels = new HashMap<>();
        labels.put("source", "default");
        metadata.setLabels(labels);

        return metadata;
    }

    public void evict(String serviceName) {
        metadataCache.invalidate(serviceName);
    }
}
