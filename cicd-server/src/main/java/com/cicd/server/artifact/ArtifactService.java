package com.cicd.server.artifact;

import com.cicd.common.enums.ArtifactType;
import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.Artifact;
import com.cicd.server.entity.Project;
import com.cicd.server.repository.ArtifactRepository;
import com.cicd.server.repository.DeploymentRepository;
import com.cicd.server.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactService {

    private static final List<PipelineStatus> ACTIVE_STATUSES = List.of(
        PipelineStatus.RUNNING,
        PipelineStatus.SUCCESS,
        PipelineStatus.PENDING
    );

    private final ArtifactRepository artifactRepository;
    private final ProjectRepository projectRepository;
    private final DeploymentRepository deploymentRepository;
    private final NexusService nexusService;
    private final HarborService harborService;

    @Value("${artifact.retention.days-retain-all:30}")
    private int daysRetainAll;

    @Value("${artifact.retention.days-retain-latest:90}")
    private int daysRetainLatest;

    @Value("${artifact.retention.latest-count:3}")
    private int latestCount;

    @Value("${artifact.cleanup.batch-size:100}")
    private int cleanupBatchSize;

    public Artifact uploadArtifact(Long projectId, String name, ArtifactType type, String version,
                                   Path filePath, String registryUrl, String repository,
                                   Long buildId, String gitCommit, String gitBranch,
                                   Map<String, String> metadata) throws Exception {
        Project project = projectRepository.findById(projectId).orElseThrow();

        Artifact artifact = new Artifact();
        artifact.setProject(project);
        artifact.setName(name);
        artifact.setType(type);
        artifact.setVersion(version);
        artifact.setRegistryUrl(registryUrl);
        artifact.setRepository(repository);
        artifact.setFileSizeBytes(Files.size(filePath));
        artifact.setMd5Hash(calculateHash(filePath, "MD5"));
        artifact.setSha256Hash(calculateHash(filePath, "SHA-256"));
        artifact.setGitCommitSha(gitCommit);
        artifact.setGitBranch(gitBranch);
        artifact.setBuildNumber(buildId != null ? buildId.intValue() : null);
        artifact.setMetadataJson(serializeMetadata(metadata));
        artifact.setExpiresAt(calculateExpiryDate());
        artifact.setCleanupStatus("NONE");

        String uploadUrl = switch (type) {
            case JAR, WAR -> nexusService.uploadMavenArtifact(registryUrl, repository, name, version, filePath);
            case DOCKER_IMAGE -> harborService.uploadDockerImage(registryUrl, repository, name, version, filePath);
            case NPM_PACKAGE -> nexusService.uploadNpmArtifact(registryUrl, repository, filePath);
            default -> null;
        };

        artifact.setUrl(uploadUrl);
        artifact.setFullPath(repository + "/" + name + ":" + version);

        return artifactRepository.save(artifact);
    }

    public Artifact getArtifact(Long id) {
        return artifactRepository.findById(id).orElse(null);
    }

    public Page<Artifact> listArtifacts(Long projectId, ArtifactType type, Pageable pageable) {
        if (type != null) {
            return artifactRepository.findByProjectIdAndType(projectId, type, pageable);
        }
        return artifactRepository.findByProjectId(projectId, pageable);
    }

    public Artifact traceArtifact(String version, String name, Long projectId) {
        return artifactRepository.findByProjectIdAndNameAndVersion(projectId, name, version).orElse(null);
    }

    public Artifact traceByCommit(String commitSha) {
        return artifactRepository.findByGitCommitSha(commitSha).orElse(null);
    }

    public void pinArtifact(Long id, boolean pinned) {
        Artifact artifact = artifactRepository.findById(id).orElseThrow();
        artifact.setIsPinned(pinned);
        if (!pinned) {
            artifact.setExpiresAt(calculateExpiryDate());
        } else {
            artifact.setExpiresAt(null);
            artifact.setCleanupStatus("NONE");
        }
        artifactRepository.save(artifact);
    }

    public void deleteArtifact(Long id) {
        artifactRepository.deleteById(id);
    }

    private boolean isArtifactInUseOrPinned(Artifact artifact) {
        if (Boolean.TRUE.equals(artifact.getIsPinned())) {
            log.debug("Artifact {}:{} is pinned, skipping cleanup",
                artifact.getName(), artifact.getVersion());
            return true;
        }
        boolean inUse = deploymentRepository.isArtifactInUse(
            artifact.getName(), artifact.getVersion(), ACTIVE_STATUSES);
        if (inUse) {
            log.debug("Artifact {}:{} is in use by deployment, skipping cleanup",
                artifact.getName(), artifact.getVersion());
        }
        return inUse;
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void markExpiredArtifactsForCleanup() {
        log.info("Starting artifact cleanup phase 1: marking expired artifacts...");

        List<Artifact> expired = artifactRepository.findExpiredArtifacts(LocalDateTime.now());
        if (!expired.isEmpty()) {
            List<Long> expiredIds = expired.stream()
                .filter(a -> !isArtifactInUseOrPinned(a))
                .map(Artifact::getId)
                .toList();
            if (!expiredIds.isEmpty()) {
                artifactRepository.updateCleanupStatusByIds(expiredIds, "PENDING");
                log.info("Marked {} expired artifacts for cleanup", expiredIds.size());
            }
        }

        markOldVersionsForCleanup();
    }

    private void markOldVersionsForCleanup() {
        LocalDateTime allRetainCutoff = LocalDateTime.now().minusDays(daysRetainAll);
        LocalDateTime latestRetainCutoff = LocalDateTime.now().minusDays(daysRetainLatest);

        List<Project> projects = projectRepository.findAll();
        for (Project project : projects) {
            List<Artifact> artifacts = artifactRepository.findArtifactsForCleanup(
                project.getId(), allRetainCutoff, latestRetainCutoff);

            Map<String, List<Artifact>> byName = artifacts.stream()
                .collect(java.util.stream.Collectors.groupingBy(Artifact::getName));

            for (Map.Entry<String, List<Artifact>> entry : byName.entrySet()) {
                List<Artifact> nameArtifacts = entry.getValue();
                if (nameArtifacts.size() > latestCount) {
                    List<Long> idsToClean = nameArtifacts.subList(latestCount, nameArtifacts.size())
                        .stream()
                        .filter(a -> !isArtifactInUseOrPinned(a))
                        .map(Artifact::getId)
                        .toList();

                    if (!idsToClean.isEmpty()) {
                        artifactRepository.updateCleanupStatusByIds(idsToClean, "PENDING");
                        log.info("Marked {} old version artifacts for cleanup: {}", idsToClean.size(), entry.getKey());
                    }
                }
            }
        }
    }

    @Scheduled(cron = "0 30 2 * * ?")
    public void executePendingCleanups() {
        log.info("Starting artifact cleanup phase 2: executing pending cleanups...");

        List<Artifact> pending = artifactRepository.findPendingCleanup();
        int deleted = 0;
        int failed = 0;

        for (Artifact artifact : pending) {
            try {
                executeCleanup(artifact);
                deleted++;
            } catch (Exception e) {
                failed++;
                log.error("Failed to cleanup artifact {}:{}, rolling back status",
                    artifact.getName(), artifact.getVersion(), e);
                rollbackCleanupStatus(artifact.getId());
            }
        }

        log.info("Artifact cleanup phase 2 completed. Deleted: {}, Failed: {}", deleted, failed);
    }

    @Transactional
    public void executeCleanup(Artifact artifact) throws Exception {
        if (isArtifactInUseOrPinned(artifact)) {
            log.warn("Artifact {}:{} is in use or pinned, skipping cleanup (defensive check)",
                artifact.getName(), artifact.getVersion());
            rollbackCleanupStatus(artifact.getId());
            return;
        }
        deleteFromRepository(artifact);
        artifactRepository.delete(artifact);
        log.info("Deleted artifact: {}:{}", artifact.getName(), artifact.getVersion());
    }

    @Transactional
    public void rollbackCleanupStatus(Long artifactId) {
        artifactRepository.updateCleanupStatus(artifactId, "NONE");
    }

    @Transactional
    public void resetStalePendingCleanups() {
        List<Artifact> stalePending = artifactRepository.findByCleanupStatus("PENDING");
        if (!stalePending.isEmpty()) {
            List<Long> ids = stalePending.stream().map(Artifact::getId).toList();
            artifactRepository.updateCleanupStatusByIds(ids, "NONE");
            log.info("Reset {} stale pending cleanup artifacts back to NONE", ids.size());
        }
    }

    private void deleteFromRepository(Artifact artifact) throws Exception {
        switch (artifact.getType()) {
            case JAR, WAR, NPM_PACKAGE -> nexusService.deleteArtifact(
                artifact.getRegistryUrl(), artifact.getRepository(), artifact.getName(), artifact.getVersion());
            case DOCKER_IMAGE -> harborService.deleteImage(
                artifact.getRegistryUrl(), artifact.getRepository(), artifact.getName(), artifact.getVersion());
        }
    }

    private LocalDateTime calculateExpiryDate() {
        return LocalDateTime.now().plusDays(daysRetainLatest);
    }

    private String calculateHash(Path filePath, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream is = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<Artifact> getArtifactHistory(Long projectId, String name, int limit) {
        return artifactRepository.findLatestByName(projectId, name, PageRequest.of(0, limit));
    }
}
