package com.cicd.runner.executor;

import com.cicd.grpc.DockerConfig;
import com.cicd.grpc.PipelineJob;
import com.cicd.grpc.PipelineStep;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.*;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;

@Slf4j
public class DockerStepExecutor implements StepExecutor {

    private final DockerClient dockerClient;

    public DockerStepExecutor(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public boolean execute(PipelineStep step, PipelineJob job, Map<String, String> env, Consumer<String> logConsumer) throws Exception {
        DockerConfig dockerConfig = step.getDocker();
        if (dockerConfig == null) {
            logConsumer.accept("ERROR: No docker config provided");
            return false;
        }

        String image = dockerConfig.getImage();
        logConsumer.accept("Pulling image: " + image);

        try {
            dockerClient.pullImageCmd(image)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Object object) {
                            logConsumer.accept("Pulling: " + object.toString());
                        }
                    }).awaitCompletion();
        } catch (Exception e) {
            log.warn("Pull image failed, trying to use local image: " + e.getMessage());
        }

        List<String> commands = dockerConfig.getCommandsList();
        String script = String.join(" && ", commands);
        if (script.isEmpty()) {
            logConsumer.accept("ERROR: No commands provided for docker step");
            return false;
        }

        String workingDir = dockerConfig.getWorkingDir();
        if (workingDir == null || workingDir.isEmpty()) {
            workingDir = "/workspace";
        }

        List<String> envVars = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            envVars.add(entry.getKey() + "=" + entry.getValue());
        }
        for (Map.Entry<String, String> entry : dockerConfig.getEnvironmentMap().entrySet()) {
            envVars.add(entry.getKey() + "=" + entry.getValue());
        }

        List<Bind> binds = new ArrayList<>();
        String hostWorkingDir = job.getWorkingDir() != null ? job.getWorkingDir() : System.getProperty("user.dir");
        binds.add(new Bind(hostWorkingDir, new Volume(workingDir)));
        for (String volumeStr : dockerConfig.getVolumesList()) {
            String[] parts = volumeStr.split(":");
            if (parts.length >= 2) {
                binds.add(new Bind(parts[0], new Volume(parts[1])));
            }
        }

        logConsumer.accept("Running in container: " + image);
        logConsumer.accept("$ " + script);

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withCmd("bash", "-c", script)
                .withWorkingDir(workingDir)
                .withEnv(envVars)
                .withBinds(binds)
                .withPrivileged(dockerConfig.getPrivileged())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(false)
                .exec();

        String containerId = container.getId();

        try {
            dockerClient.startContainerCmd(containerId).exec();

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTailAll()
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            String message = new String(frame.getPayload()).trim();
                            if (!message.isEmpty()) {
                                logConsumer.accept(message);
                                if (frame.getStreamType() == Frame.StreamType.STDOUT) {
                                    stdout.write(frame.getPayload(), 0, frame.getPayload().length);
                                } else {
                                    stderr.write(frame.getPayload(), 0, frame.getPayload().length);
                                }
                            }
                        }
                    }).awaitCompletion();

            int exitCode = dockerClient.waitContainerCmd(containerId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode();

            if (exitCode != 0) {
                logConsumer.accept("ERROR: Container exited with code " + exitCode);
                return step.getContinueOnError();
            }

            logConsumer.accept("Docker step completed successfully");
            return true;
        } finally {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Failed to remove container " + containerId, e);
            }
        }
    }
}
