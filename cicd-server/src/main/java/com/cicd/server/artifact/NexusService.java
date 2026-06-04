package com.cicd.server.artifact;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class NexusService {

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public String uploadMavenArtifact(String nexusUrl, String repository, String name,
                                       String version, Path filePath) throws Exception {
        String groupId = name.replace(".", "/");
        String artifactId = extractArtifactId(name);
        String extension = Files.probeContentType(filePath);
        extension = extension != null && extension.contains("java-archive") ? "jar" : "jar";

        String uploadUrl = String.format("%s/repository/%s/%s/%s/%s/%s-%s.%s",
            nexusUrl, repository, groupId, artifactId, version, artifactId, version, extension);

        byte[] content = Files.readAllBytes(filePath);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .header("Content-Type", "application/java-archive")
            .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to upload to Nexus: " + response.statusCode());
        }

        log.info("Uploaded Maven artifact: {}:{} to {}", name, version, repository);
        return uploadUrl;
    }

    public String uploadNpmArtifact(String nexusUrl, String repository, Path filePath) throws Exception {
        String uploadUrl = String.format("%s/repository/%s/", nexusUrl, repository);

        byte[] content = Files.readAllBytes(filePath);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(uploadUrl))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
            .header("Content-Type", "application/json")
            .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Failed to upload npm package: " + response.statusCode());
        }

        log.info("Uploaded npm package to {}", repository);
        return uploadUrl;
    }

    public void deleteArtifact(String nexusUrl, String repository, String name, String version) throws Exception {
        String groupId = name.replace(".", "/");
        String artifactId = extractArtifactId(name);
        String deleteUrl = String.format("%s/service/rest/v1/components?repository=%s",
            nexusUrl, repository);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(deleteUrl))
            .DELETE()
            .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        log.info("Deleted artifact {}:{} from {}", name, version, repository);
    }

    private String extractArtifactId(String name) {
        int lastDot = name.lastIndexOf('.');
        return lastDot > 0 ? name.substring(lastDot + 1) : name;
    }

    private String basicAuth(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        return "Basic " + encoded;
    }
}
