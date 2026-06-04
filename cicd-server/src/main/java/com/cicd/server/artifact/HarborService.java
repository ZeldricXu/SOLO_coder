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

@Slf4j
@Service
@RequiredArgsConstructor
public class HarborService {

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public String uploadDockerImage(String harborUrl, String project, String name,
                                    String tag, Path tarballPath) throws Exception {
        String imageRef = String.format("%s/%s/%s:%s",
            harborUrl.replace("http://", "").replace("https://", ""),
            project, name, tag);

        ProcessBuilder pb = new ProcessBuilder(
            "docker", "load", "-i", tarballPath.toString()
        );
        Process load = pb.start();
        load.waitFor();

        pb = new ProcessBuilder("docker", "tag", name + ":" + tag, imageRef);
        pb.start().waitFor();

        pb = new ProcessBuilder("docker", "push", imageRef);
        Process push = pb.start();
        int exitCode = push.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to push Docker image");
        }

        log.info("Pushed Docker image: {}", imageRef);
        return imageRef;
    }

    public void deleteImage(String harborUrl, String project, String name, String tag) throws Exception {
        String apiUrl = String.format("%s/api/v2.0/projects/%s/repositories/%s/artifacts/%s",
            harborUrl, project, name, tag);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .DELETE()
            .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        log.info("Deleted Docker image: {}/{}/{}:{}", harborUrl, project, name, tag);
    }
}
