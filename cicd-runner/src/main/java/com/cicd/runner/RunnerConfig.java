package com.cicd.runner;

import lombok.Data;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@Data
public class RunnerConfig {
    private String name;
    private String token;
    private String serverHost;
    private int serverPort;
    private List<String> tags;
    private String workingDir;
    private int heartbeatInterval;
    private String hostname;
    private String ipAddress;
    private String os;
    private String architecture;
    private int cpuCores;
    private int memoryMb;
    private String version = "1.0.0";

    public static RunnerConfig load() {
        RunnerConfig config = new RunnerConfig();
        Properties props = new Properties();

        try (InputStream is = RunnerConfig.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load application.yml, using environment variables");
        }

        config.name = System.getenv().getOrDefault("RUNNER_NAME", "default-runner");
        config.token = System.getenv().getOrDefault("RUNNER_TOKEN", "");
        config.serverHost = System.getenv().getOrDefault("SERVER_HOST", "localhost");
        config.serverPort = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "50051"));
        String tagsStr = System.getenv().getOrDefault("RUNNER_TAGS", "");
        config.tags = Arrays.asList(tagsStr.split(",")).stream().map(String::trim).filter(t -> !t.isEmpty()).toList();
        config.workingDir = System.getenv().getOrDefault("RUNNER_WORKING_DIR", "/opt/cicd-runner");
        config.heartbeatInterval = Integer.parseInt(System.getenv().getOrDefault("HEARTBEAT_INTERVAL", "30"));

        try {
            config.hostname = InetAddress.getLocalHost().getHostName();
            config.ipAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            config.hostname = "unknown";
            config.ipAddress = "127.0.0.1";
        }

        config.os = System.getProperty("os.name");
        config.architecture = System.getProperty("os.arch");
        config.cpuCores = Runtime.getRuntime().availableProcessors();
        config.memoryMb = (int) (Runtime.getRuntime().totalMemory() / 1024 / 1024);

        return config;
    }
}
