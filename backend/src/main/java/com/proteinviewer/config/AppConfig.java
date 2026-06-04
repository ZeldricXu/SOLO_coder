package com.proteinviewer.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableScheduling
public class AppConfig {

    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    @Value("${minio.access-key:minioadmin}")
    private String minioAccessKey;

    @Value("${minio.secret-key:minioadmin}")
    private String minioSecretKey;

    @Value("${cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS,PATCH}")
    private String[] allowedMethods;

    @Value("${cors.allowed-headers:*}")
    private String allowedHeaders;

    @Value("${cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Value("${cors.max-age:3600}")
    private long maxAge;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioEndpoint)
                .credentials(minioAccessKey, minioSecretKey)
                .build();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                List<String> origins = Arrays.asList(allowedOrigins);
                if (origins.contains("*")) {
                    registry.addMapping("/api/**")
                            .allowedOriginPatterns("*")
                            .allowedMethods(allowedMethods)
                            .allowedHeaders(allowedHeaders)
                            .allowCredentials(allowCredentials)
                            .maxAge(maxAge);
                } else {
                    registry.addMapping("/api/**")
                            .allowedOrigins(allowedOrigins)
                            .allowedMethods(allowedMethods)
                            .allowedHeaders(allowedHeaders)
                            .allowCredentials(allowCredentials)
                            .maxAge(maxAge);
                }
            }
        };
    }
}
