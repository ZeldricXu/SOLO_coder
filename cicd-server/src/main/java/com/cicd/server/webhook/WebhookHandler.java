package com.cicd.server.webhook;

import com.cicd.common.dto.pipeline.PipelineDefinition;
import com.cicd.common.enums.TriggerType;
import com.cicd.common.util.GitUtils;
import com.cicd.common.util.YamlParser;
import com.cicd.server.entity.Pipeline;
import com.cicd.server.entity.Project;
import com.cicd.server.entity.WebhookEvent;
import com.cicd.server.pipeline.PipelineService;
import com.cicd.server.repository.PipelineRepository;
import com.cicd.server.repository.ProjectRepository;
import com.cicd.server.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookHandler {

    private final PipelineService pipelineService;
    private final PipelineRepository pipelineRepository;
    private final ProjectRepository projectRepository;
    private final WebhookEventRepository eventRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void processGitLabWebhook(Long projectId, String eventType, String token, String payload, WebhookEvent event) {
        try {
            Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

            if (project.getWebhookSecret() != null && !project.getWebhookSecret().equals(token)) {
                throw new SecurityException("Invalid webhook token");
            }

            JsonNode json = objectMapper.readTree(payload);

            String branch = null;
            String commitSha = null;
            String tagName = null;
            String sender = null;
            List<String> changedFiles = new ArrayList<>();

            TriggerType triggerType = switch (eventType) {
                case "Push Hook" -> {
                    branch = json.path("ref").asText().replace("refs/heads/", "");
                    commitSha = json.path("after").asText();
                    sender = json.path("user_name").asText();
                    JsonNode commits = json.path("commits");
                    for (JsonNode commit : commits) {
                        JsonNode added = commit.path("added");
                        JsonNode modified = commit.path("modified");
                        for (JsonNode file : added) {
                            changedFiles.add(file.asText());
                        }
                        for (JsonNode file : modified) {
                            changedFiles.add(file.asText());
                        }
                    }
                    yield TriggerType.WEBHOOK_PUSH;
                }
                case "Merge Request Hook" -> {
                    branch = json.path("object_attributes").path("source_branch").asText();
                    commitSha = json.path("object_attributes").path("last_commit").path("id").asText();
                    sender = json.path("user").path("name").asText();
                    yield TriggerType.WEBHOOK_PR;
                }
                case "Tag Push Hook" -> {
                    tagName = json.path("ref").asText().replace("refs/tags/", "");
                    commitSha = json.path("after").asText();
                    sender = json.path("user_name").asText();
                    yield TriggerType.WEBHOOK_TAG;
                }
                default -> {
                    log.info("Ignoring unsupported GitLab event: {}", eventType);
                    event.setProcessed(true);
                    eventRepository.save(event);
                    yield null;
                }
            };

            if (triggerType == null) return;

            event.setBranchName(branch);
            event.setCommitSha(commitSha);
            event.setTagName(tagName);
            event.setSender(sender);
            event.setRepoUrl(json.path("repository").path("url").asText());

            triggerMatchingPipelines(project, triggerType, branch, commitSha, sender, changedFiles, event);

        } catch (Exception e) {
            log.error("Error processing GitLab webhook", e);
            throw new RuntimeException(e);
        }
    }

    public void processGitHubWebhook(Long projectId, String eventType, String signature, String payload, WebhookEvent event) {
        try {
            Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

            JsonNode json = objectMapper.readTree(payload);

            String branch = null;
            String commitSha = null;
            String tagName = null;
            String sender = null;
            List<String> changedFiles = new ArrayList<>();

            TriggerType triggerType = switch (eventType) {
                case "push" -> {
                    branch = json.path("ref").asText().replace("refs/heads/", "");
                    commitSha = json.path("after").asText();
                    sender = json.path("sender").path("login").asText();
                    JsonNode commits = json.path("commits");
                    for (JsonNode commit : commits) {
                        JsonNode added = commit.path("added");
                        JsonNode modified = commit.path("modified");
                        for (JsonNode file : added) {
                            changedFiles.add(file.asText());
                        }
                        for (JsonNode file : modified) {
                            changedFiles.add(file.asText());
                        }
                    }
                    yield TriggerType.WEBHOOK_PUSH;
                }
                case "pull_request" -> {
                    String action = json.path("action").asText();
                    if (!List.of("opened", "synchronize", "reopened").contains(action)) {
                        log.info("Ignoring PR action: {}", action);
                        event.setProcessed(true);
                        eventRepository.save(event);
                        yield null;
                    }
                    branch = json.path("pull_request").path("head").path("ref").asText();
                    commitSha = json.path("pull_request").path("head").path("sha").asText();
                    sender = json.path("sender").path("login").asText();
                    yield TriggerType.WEBHOOK_PR;
                }
                case "create" -> {
                    String refType = json.path("ref_type").asText();
                    if (!"tag".equals(refType)) {
                        event.setProcessed(true);
                        eventRepository.save(event);
                        yield null;
                    }
                    tagName = json.path("ref").asText();
                    sender = json.path("sender").path("login").asText();
                    yield TriggerType.WEBHOOK_TAG;
                }
                default -> {
                    log.info("Ignoring unsupported GitHub event: {}", eventType);
                    event.setProcessed(true);
                    eventRepository.save(event);
                    yield null;
                }
            };

            if (triggerType == null) return;

            event.setBranchName(branch);
            event.setCommitSha(commitSha);
            event.setTagName(tagName);
            event.setSender(sender);
            event.setRepoUrl(json.path("repository").path("url").asText());

            triggerMatchingPipelines(project, triggerType, branch, commitSha, sender, changedFiles, event);

        } catch (Exception e) {
            log.error("Error processing GitHub webhook", e);
            throw new RuntimeException(e);
        }
    }

    private void triggerMatchingPipelines(Project project, TriggerType triggerType, String branch,
                                          String commitSha, String sender, List<String> changedFiles,
                                          WebhookEvent event) {
        List<Pipeline> pipelines = pipelineRepository.findByProjectIdAndIsActiveTrue(project.getId());

        for (Pipeline pipeline : pipelines) {
            try {
                PipelineDefinition definition;
                try {
                    definition = YamlParser.parse(pipeline.getYamlDefinition());
                } catch (YamlParser.PipelineValidationException e) {
                    log.error("Invalid pipeline YAML for pipeline {}", pipeline.getId(), e);
                    continue;
                }

                if (definition.getTrigger() == null || definition.getTrigger().getWebhooks() == null) {
                    continue;
                }

                for (var trigger : definition.getTrigger().getWebhooks()) {
                    if (matchesTrigger(trigger, triggerType, branch, changedFiles)) {
                        Map<String, String> params = new HashMap<>();
                        params.put("GIT_COMMIT", commitSha);
                        params.put("GIT_BRANCH", branch);
                        params.put("GIT_AUTHOR", sender);

                        var execution = pipelineService.triggerPipeline(
                            pipeline.getId(), triggerType, sender, branch, params
                        );

                        event.setTriggeredExecutionId(execution.getId());
                        log.info("Triggered pipeline {} from webhook", pipeline.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to process pipeline {} for webhook", pipeline.getId(), e);
            }
        }

        event.setProcessed(true);
        eventRepository.save(event);
    }

    private boolean matchesTrigger(com.cicd.common.dto.pipeline.WebhookTrigger trigger,
                                   TriggerType eventType, String branch, List<String> changedFiles) {
        String triggerEventType = trigger.getType();
        boolean typeMatches = switch (eventType) {
            case WEBHOOK_PUSH -> "push".equalsIgnoreCase(triggerEventType);
            case WEBHOOK_PR -> "pr".equalsIgnoreCase(triggerEventType) || "pull_request".equalsIgnoreCase(triggerEventType);
            case WEBHOOK_TAG -> "tag".equalsIgnoreCase(triggerEventType);
            default -> false;
        };

        if (!typeMatches) return false;

        if (trigger.getBranchPattern() != null && !trigger.getBranchPattern().isEmpty()) {
            if (!GitUtils.matchesBranchPattern(branch, trigger.getBranchPattern())) {
                return false;
            }
        }

        if (trigger.getBranches() != null && !trigger.getBranches().isEmpty()) {
            boolean branchMatches = trigger.getBranches().stream()
                .anyMatch(pattern -> GitUtils.matchesBranchPattern(branch, pattern));
            if (!branchMatches) return false;
        }

        if (trigger.getPaths() != null && !trigger.getPaths().isEmpty()) {
            boolean anyPathMatches = changedFiles.stream()
                .anyMatch(file -> GitUtils.isPathIncluded(
                    file,
                    trigger.getPaths().toArray(new String[0]),
                    trigger.getIgnorePaths() != null ? trigger.getIgnorePaths().toArray(new String[0]) : null
                ));
            if (!anyPathMatches) return false;
        }

        return true;
    }
}
