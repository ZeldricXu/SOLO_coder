package com.cicd.runner.executor;

import com.cicd.grpc.PipelineJob;
import com.cicd.grpc.PipelineStep;
import com.cicd.grpc.PushConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import lombok.extern.slf4j.Slf4j;
import java.util.function.Consumer;

@Slf4j
public class PushStepExecutor implements StepExecutor {

    private final DockerClient dockerClient;

    public PushStepExecutor(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public boolean execute(PipelineStep step, PipelineJob job, java.util.Map<String, String> env, Consumer<String> logConsumer) throws Exception {
        PushConfig pushConfig = step.getPush();
        if (pushConfig == null) {
            logConsumer.accept("ERROR: No push config provided");
            return false;
        }

        String registry = pushConfig.getRegistry();
        String image = pushConfig.getImage();
        String tag = pushConfig.getTag();
        String username = pushConfig.getUsername();
        String password = pushConfig.getPassword();

        String fullImage = (registry != null && !registry.isEmpty() ? registry + "/" : "") + image + ":" + tag;
        logConsumer.accept("Pushing image: " + fullImage);

        if (username != null && !username.isEmpty()) {
            try {
                dockerClient.authCmd()
                        .withUsername(username)
                        .withPassword(password)
                        .withRegistryAddress(registry)
                        .exec();
                logConsumer.accept("Authenticated with registry: " + registry);
            } catch (Exception e) {
                logConsumer.accept("WARNING: Authentication failed, trying without auth: " + e.getMessage());
            }
        }

        try {
            dockerClient.tagImageCmd(image + ":" + tag, fullImage, tag).exec();
        } catch (Exception e) {
            log.warn("Tag image failed, maybe already exists: " + e.getMessage());
        }

        try {
            dockerClient.pushImageCmd(fullImage)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Object object) {
                            String msg = object.toString();
                            if (msg.contains("ProgressDetail") || msg.contains("progress")) {
                                if (msg.contains("\"status\":\"Pushing\"")) {
                                    logConsumer.accept("Pushing... " + extractProgress(msg));
                                }
                            } else {
                                logConsumer.accept(msg);
                            }
                        }
                    }).awaitCompletion();

            logConsumer.accept("Successfully pushed image: " + fullImage);
            return true;
        } catch (Exception e) {
            logConsumer.accept("ERROR: Failed to push image: " + e.getMessage());
            log.error("Push failed", e);
            return step.getContinueOnError();
        }
    }

    private String extractProgress(String msg) {
        try {
            int start = msg.indexOf("current");
            if (start > 0) {
                int end = msg.indexOf("}", start);
                if (end > 0) {
                    return msg.substring(start, end + 1);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
}
