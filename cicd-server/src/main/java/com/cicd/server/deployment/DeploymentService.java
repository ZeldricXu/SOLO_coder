package com.cicd.server.deployment;

import com.cicd.common.dto.pipeline.DeployConfig;
import com.cicd.common.enums.DeploymentStrategy;
import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.Deployment;
import com.cicd.server.entity.Environment;
import com.cicd.server.entity.Project;
import com.cicd.server.repository.DeploymentRepository;
import com.cicd.server.repository.EnvironmentRepository;
import com.cicd.server.repository.ProjectRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final EnvironmentRepository environmentRepository;
    private final ProjectRepository projectRepository;
    private final SmokeTestService smokeTestService;
    private final Map<Long, ApiClient> k8sClients = new ConcurrentHashMap<>();

    @Async
    public void executeDeployment(Long deploymentId, DeployConfig config, Map<String, String> params) {
        Deployment deployment = deploymentRepository.findById(deploymentId)
            .orElseThrow(() -> new IllegalArgumentException("Deployment not found: " + deploymentId));

        try {
            deployment.setStatus(PipelineStatus.RUNNING);
            deployment.setStartedAt(LocalDateTime.now());
            deploymentRepository.save(deployment);

            ApiClient client = getK8sClient(deployment.getEnvironment());
            Configuration.setDefaultApiClient(client);

            switch (config.getStrategy()) {
                case ROLLING_UPDATE -> performRollingUpdate(deployment, config, params);
                case BLUE_GREEN -> performBlueGreenDeployment(deployment, config, params);
                case CANARY -> performCanaryDeployment(deployment, config, params);
            }

            if (config.getSmokeTest() != null && config.getSmokeTest().getEnabled()) {
                boolean smokePassed = smokeTestService.runSmokeTests(
                    deployment.getEnvironment().getIngressDomain(),
                    config.getSmokeTest()
                );
                deployment.setSmokeTestPassed(smokePassed);

                if (!smokePassed && config.getAutoRollback() != null && config.getAutoRollback().getEnabled()) {
                    log.warn("Smoke test failed, auto rolling back deployment {}", deploymentId);
                    rollbackDeployment(deploymentId, "Smoke test failed");
                    return;
                }
            }

            deployment.setStatus(PipelineStatus.SUCCESS);
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setDurationSeconds(java.time.Duration.between(
                deployment.getStartedAt(), deployment.getFinishedAt()).getSeconds());
            deploymentRepository.save(deployment);

            log.info("Deployment {} completed successfully", deploymentId);

        } catch (Exception e) {
            log.error("Deployment {} failed", deploymentId, e);
            deployment.setStatus(PipelineStatus.FAILED);
            deployment.setFinishedAt(LocalDateTime.now());
            deployment.setErrorMessage(e.getMessage());
            deploymentRepository.save(deployment);
        }
    }

    private void performRollingUpdate(Deployment deployment, DeployConfig config, Map<String, String> params) throws Exception {
        AppsV1Api appsV1Api = new AppsV1Api();
        String namespace = config.getNamespace() != null ? config.getNamespace() : deployment.getEnvironment().getNamespace();
        String appName = config.getAppName() != null ? config.getAppName() : deployment.getAppName();

        V1Deployment existing = appsV1Api.readNamespacedDeployment(appName, namespace, null);

        if (existing == null) {
            V1Deployment newDeploy = createDeploymentManifest(appName, namespace, config, params);
            appsV1Api.createNamespacedDeployment(namespace, newDeploy, null, null, null, null);
            log.info("Created new deployment: {}/{}", namespace, appName);
        } else {
            V1Deployment updated = updateDeploymentImage(existing, config.getImage(), params);
            appsV1Api.replaceNamespacedDeployment(appName, namespace, updated, null, null, null, null);
            log.info("Updated deployment: {}/{}", namespace, appName);
        }

        waitForDeploymentReady(appsV1Api, namespace, appName, config.getReplicas());
        deployment.setCurrentReplicas(config.getReplicas());
    }

    private void performBlueGreenDeployment(Deployment deployment, DeployConfig config, Map<String, String> params) throws Exception {
        AppsV1Api appsV1Api = new AppsV1Api();
        CoreV1Api coreV1Api = new CoreV1Api();
        String namespace = config.getNamespace() != null ? config.getNamespace() : deployment.getEnvironment().getNamespace();
        String appName = config.getAppName() != null ? config.getAppName() : deployment.getAppName();

        String blueLabel = config.getBlueGreen() != null ? config.getBlueGreen().getBlueLabel() : "blue";
        String greenLabel = config.getBlueGreen() != null ? config.getBlueGreen().getGreenLabel() : "green";

        String currentColor = getCurrentDeploymentColor(coreV1Api, namespace, appName);
        String newColor = "blue".equals(currentColor) ? "green" : "blue";
        String newVersion = appName + "-" + newColor;

        V1Deployment newDeploy = createDeploymentManifest(newVersion, namespace, config, params);
        newDeploy.getMetadata().getLabels().put("color", newColor);
        newDeploy.getSpec().getTemplate().getMetadata().getLabels().put("color", newColor);
        appsV1Api.createNamespacedDeployment(namespace, newDeploy, null, null, null, null);

        waitForDeploymentReady(appsV1Api, namespace, newVersion, config.getReplicas());

        boolean autoSwitch = config.getBlueGreen() == null || config.getBlueGreen().getAutoSwitch();
        if (autoSwitch) {
            int delay = config.getBlueGreen() != null ? config.getBlueGreen().getSwitchDelay() : 30;
            Thread.sleep(delay * 1000L);

            switchServiceColor(coreV1Api, namespace, appName, newColor);
            log.info("Switched service {} to {}", appName, newColor);

            deleteOldDeployment(appsV1Api, namespace, appName + "-" + currentColor);
        }

        deployment.setCurrentReplicas(config.getReplicas());
    }

    private void performCanaryDeployment(Deployment deployment, DeployConfig config, Map<String, String> params) throws Exception {
        AppsV1Api appsV1Api = new AppsV1Api();
        CoreV1Api coreV1Api = new CoreV1Api();
        String namespace = config.getNamespace() != null ? config.getNamespace() : deployment.getEnvironment().getNamespace();
        String appName = config.getAppName() != null ? config.getAppName() : deployment.getAppName();

        V1Deployment canaryDeploy = createDeploymentManifest(appName + "-canary", namespace, config, params);
        canaryDeploy.getMetadata().getLabels().put("track", "canary");
        canaryDeploy.getSpec().getTemplate().getMetadata().getLabels().put("track", "canary");
        appsV1Api.createNamespacedDeployment(namespace, canaryDeploy, null, null, null, null);

        waitForDeploymentReady(appsV1Api, namespace, appName + "-canary", 1);

        var canaryConfig = config.getCanary();
        List<Integer> trafficSteps = canaryConfig != null ? canaryConfig.getTrafficSteps() : List.of(10, 25, 50, 75, 100);
        int stepInterval = canaryConfig != null ? canaryConfig.getStepInterval() : 300;

        for (Integer trafficPercent : trafficSteps) {
            updateCanaryTraffic(coreV1Api, namespace, appName, trafficPercent);
            deployment.setCanaryTrafficPercent(trafficPercent);
            deploymentRepository.save(deployment);

            Thread.sleep(stepInterval * 1000L);

            if (canaryConfig != null && canaryConfig.getMaxErrorRate() > 0) {
                if (!checkCanaryMetrics(config, canaryConfig.getMaxErrorRate())) {
                    log.warn("Canary error rate exceeded, rolling back");
                    rollbackCanary(appsV1Api, coreV1Api, namespace, appName);
                    deployment.setStatus(PipelineStatus.FAILED);
                    deployment.setErrorMessage("Canary deployment failed due to high error rate");
                    deploymentRepository.save(deployment);
                    return;
                }
            }
        }

        V1Deployment mainDeploy = appsV1Api.readNamespacedDeployment(appName, namespace, null);
        updateDeploymentImage(mainDeploy, config.getImage(), params);
        appsV1Api.replaceNamespacedDeployment(appName, namespace, mainDeploy, null, null, null, null);

        waitForDeploymentReady(appsV1Api, namespace, appName, config.getReplicas());

        appsV1Api.deleteNamespacedDeployment(appName + "-canary", namespace, null, null, null, null, null, null);
        updateCanaryTraffic(coreV1Api, namespace, appName, 0);

        deployment.setCurrentReplicas(config.getReplicas());
        deployment.setCanaryTrafficPercent(100);
    }

    private V1Deployment createDeploymentManifest(String name, String namespace, DeployConfig config, Map<String, String> params) {
        Map<String, String> labels = new HashMap<>();
        labels.put("app", name);
        if (config.getLabels() != null) {
            labels.putAll(config.getLabels());
        }

        Map<String, String> envVars = new HashMap<>();
        if (config.getEnv() != null) {
            envVars.putAll(config.getEnv());
        }
        if (params != null) {
            envVars.putAll(params);
        }

        List<V1EnvVar> k8sEnvVars = envVars.entrySet().stream()
            .map(e -> new V1EnvVar().name(e.getKey()).value(e.getValue()))
            .toList();

        V1PodSpec podSpec = new V1PodSpec()
            .addContainersItem(new V1Container()
                .name(name)
                .image(config.getImage())
                .addPortsItem(new V1ContainerPort().containerPort(Integer.parseInt(config.getServicePort() != null ? config.getServicePort() : "8080")))
                .env(k8sEnvVars)
                .livenessProbe(createProbe(config))
                .readinessProbe(createProbe(config)));

        V1DeploymentSpec spec = new V1DeploymentSpec()
            .replicas(config.getReplicas() > 0 ? config.getReplicas() : 1)
            .selector(new V1LabelSelector().matchLabels(labels))
            .template(new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(labels))
                .spec(podSpec));

        return new V1Deployment()
            .metadata(new V1ObjectMeta().name(name).namespace(namespace).labels(labels))
            .spec(spec);
    }

    private V1Probe createProbe(DeployConfig config) {
        if (config.getHealthCheckPath() == null) return null;

        return new V1Probe()
            .httpGet(new V1HTTPGetAction()
                .path(config.getHealthCheckPath())
                .port(new IntOrString(Integer.parseInt(config.getServicePort() != null ? config.getServicePort() : "8080"))))
            .initialDelaySeconds(10)
            .periodSeconds(10)
            .timeoutSeconds(config.getHealthCheckTimeout() > 0 ? config.getHealthCheckTimeout() : 5);
    }

    private V1Deployment updateDeploymentImage(V1Deployment deploy, String image, Map<String, String> params) {
        deploy.getSpec().getTemplate().getSpec().getContainers().get(0).setImage(image);
        return deploy;
    }

    private void waitForDeploymentReady(AppsV1Api api, String namespace, String name, int replicas) throws Exception {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            V1Deployment deploy = api.readNamespacedDeploymentStatus(name, namespace, null);
            V1DeploymentStatus status = deploy.getStatus();

            if (status != null && status.getReadyReplicas() != null && status.getReadyReplicas() >= replicas) {
                log.info("Deployment {}/{} is ready", namespace, name);
                return;
            }

            Thread.sleep(5000);
        }
        throw new RuntimeException("Timeout waiting for deployment " + namespace + "/" + name);
    }

    private String getCurrentDeploymentColor(CoreV1Api api, String namespace, String appName) throws Exception {
        V1Service service = api.readNamespacedService(appName, namespace, null);
        String color = service.getSpec().getSelector().get("color");
        return color != null ? color : "blue";
    }

    private void switchServiceColor(CoreV1Api api, String namespace, String appName, String color) throws Exception {
        V1Service service = api.readNamespacedService(appName, namespace, null);
        service.getSpec().getSelector().put("color", color);
        api.replaceNamespacedService(appName, namespace, service, null, null, null, null);
    }

    private void deleteOldDeployment(AppsV1Api api, String namespace, String name) throws Exception {
        api.deleteNamespacedDeployment(name, namespace, null, null, null, null, null, null);
    }

    private void updateCanaryTraffic(CoreV1Api api, String namespace, String appName, int canaryPercent) throws Exception {
        V1Service service = api.readNamespacedService(appName, namespace, null);
        if (canaryPercent > 0) {
            service.getMetadata().getAnnotations().put("traefik.ingress.kubernetes.io/canary", "true");
            service.getMetadata().getAnnotations().put("traefik.ingress.kubernetes.io/canary-weight", String.valueOf(canaryPercent));
        } else {
            service.getMetadata().getAnnotations().remove("traefik.ingress.kubernetes.io/canary");
            service.getMetadata().getAnnotations().remove("traefik.ingress.kubernetes.io/canary-weight");
        }
        api.replaceNamespacedService(appName, namespace, service, null, null, null, null);
    }

    private boolean checkCanaryMetrics(DeployConfig config, double maxErrorRate) {
        return true;
    }

    private void rollbackCanary(AppsV1Api appsV1Api, CoreV1Api coreV1Api, String namespace, String appName) throws Exception {
        appsV1Api.deleteNamespacedDeployment(appName + "-canary", namespace, null, null, null, null, null, null);
        updateCanaryTraffic(coreV1Api, namespace, appName, 0);
    }

    public void rollbackDeployment(Long deploymentId, String reason) {
        Deployment deployment = deploymentRepository.findById(deploymentId).orElseThrow();

        if (deployment.getPreviousDeploymentId() != null) {
            Deployment previous = deploymentRepository.findById(deployment.getPreviousDeploymentId()).orElseThrow();

            try {
                DeployConfig config = new DeployConfig();
                config.setImage(previous.getImage());
                config.setStrategy(DeploymentStrategy.ROLLING_UPDATE);
                config.setReplicas(previous.getTargetReplicas() != null ? previous.getTargetReplicas() : 1);

                executeDeployment(deploymentId, config, new HashMap<>());
            } catch (Exception e) {
                log.error("Failed to rollback deployment", e);
            }
        }

        deployment.setIsRollback(true);
        deployment.setRollbackReason(reason);
        deployment.setStatus(PipelineStatus.FAILED);
        deploymentRepository.save(deployment);
    }

    private ApiClient getK8sClient(Environment environment) throws Exception {
        Long envId = environment.getId();
        if (k8sClients.containsKey(envId)) {
            return k8sClients.get(envId);
        }

        ApiClient client = Config.defaultClient();
        k8sClients.put(envId, client);
        return client;
    }

    public Deployment createDeployment(Long projectId, Long environmentId, String appName,
                                       String version, String image, DeploymentStrategy strategy) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        Environment environment = environmentRepository.findById(environmentId).orElseThrow();

        Integer nextNumber = deploymentRepository.findMaxDeploymentNumber(projectId, environmentId);
        nextNumber = nextNumber == null ? 1 : nextNumber + 1;

        Deployment deployment = new Deployment();
        deployment.setProject(project);
        deployment.setEnvironment(environment);
        deployment.setDeploymentNumber(nextNumber);
        deployment.setAppName(appName);
        deployment.setVersion(version);
        deployment.setImage(image);
        deployment.setStrategy(strategy);
        deployment.setStatus(PipelineStatus.PENDING);
        deployment.setTargetReplicas(1);
        deployment.setCurrentReplicas(0);

        return deploymentRepository.save(deployment);
    }

    public Page<Deployment> getDeployments(Long projectId, Long environmentId, org.springframework.data.domain.Pageable pageable) {
        if (environmentId != null) {
            return deploymentRepository.findByProjectIdAndEnvironmentId(projectId, environmentId, pageable);
        }
        return deploymentRepository.findByProjectId(projectId, pageable);
    }

    public Deployment getDeployment(Long id) {
        return deploymentRepository.findById(id).orElse(null);
    }

    public Deployment getLatestDeployment(Long projectId, Long environmentId) {
        return deploymentRepository.findFirstByProjectIdAndEnvironmentIdAndStatusOrderByCreatedAtDesc(
            projectId, environmentId, "SUCCESS").orElse(null);
    }
}
