package com.chaoslab.modules.image.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.entity.ImageLayer;
import com.chaoslab.entity.ImageRepository;
import com.chaoslab.entity.ImageSyncTask;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.ImageLayerMapper;
import com.chaoslab.mapper.ImageRepositoryMapper;
import com.chaoslab.mapper.ImageSyncTaskMapper;
import com.chaoslab.modules.image.dto.ImagePullResponse;
import com.chaoslab.modules.image.dto.ImageSyncRequest;
import com.chaoslab.modules.image.dto.LayerInfo;
import com.chaoslab.modules.image.dto.RepositoryCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDistributionService {

    private final ImageRepositoryMapper repositoryMapper;
    private final ImageLayerMapper layerMapper;
    private final ImageSyncTaskMapper syncTaskMapper;

    private final Map<String, ImagePullResponse> pullProgressCache = new ConcurrentHashMap<>();

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ImageRepository> createRepository(RepositoryCreateRequest request) {
        return Mono.fromCallable(() -> {
            ImageRepository repo = new ImageRepository();
            repo.setRepoId("ir-" + UUID.randomUUID().toString().substring(0, 8));
            repo.setName(request.getName());
            repo.setRegistryUrl(request.getRegistryUrl());
            repo.setNamespace(request.getNamespace());
            repo.setAuthType(request.getAuthType());
            repo.setUsername(request.getUsername());
            repo.setPasswordEncrypted(encryptPassword(request.getPassword()));
            repo.setTlsVerify(request.getTlsVerify());
            repo.setStatus("active");

            repositoryMapper.insert(repo);
            log.info("Created image repository: {}", repo.getRepoId());
            return repo;
        });
    }

    public Mono<List<ImageRepository>> listRepositories(String status) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ImageRepository> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ImageRepository::getStatus, status);
            }
            wrapper.orderByDesc(ImageRepository::getCreatedAt);
            return repositoryMapper.selectList(wrapper);
        });
    }

    public Mono<ImageRepository> getRepository(String repoId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ImageRepository> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ImageRepository::getRepoId, repoId);
            ImageRepository repo = repositoryMapper.selectOne(wrapper);
            if (repo == null) {
                throw BusinessException.notFound("镜像仓库不存在: " + repoId);
            }
            return repo;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<ImageSyncTask> createSyncTask(ImageSyncRequest request) {
        return getRepository(request.getSourceRepoId())
                .zipWith(getRepository(request.getTargetRepoId()))
                .flatMap(tuple -> {
                    ImageRepository source = tuple.getT1();
                    ImageRepository target = tuple.getT2();
                    return Mono.fromCallable(() -> {
                        ImageSyncTask task = new ImageSyncTask();
                        task.setTaskId("ist-" + UUID.randomUUID().toString().substring(0, 8));
                        task.setSourceRepoId(request.getSourceRepoId());
                        task.setTargetRepoId(request.getTargetRepoId());
                        task.setImageReference(request.getImageReference());
                        task.setStrategy(request.getStrategy());
                        task.setP2pEnabled(request.getP2pEnabled());
                        task.setStatus("pending");
                        task.setProgress(BigDecimal.ZERO);

                        syncTaskMapper.insert(task);
                        log.info("Created image sync task: {} for {}", task.getTaskId(), request.getImageReference());

                        startSyncAsync(task);
                        return task;
                    });
                });
    }

    public Mono<ImageSyncTask> getSyncTask(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ImageSyncTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ImageSyncTask::getTaskId, taskId);
            ImageSyncTask task = syncTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw BusinessException.notFound("同步任务不存在: " + taskId);
            }
            return task;
        });
    }

    public Mono<List<ImageSyncTask>> listSyncTasks(String status) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ImageSyncTask> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(ImageSyncTask::getStatus, status);
            }
            wrapper.orderByDesc(ImageSyncTask::getCreatedAt);
            return syncTaskMapper.selectList(wrapper);
        });
    }

    public Mono<ImagePullResponse> pullImage(String repoId, String imageReference, boolean useP2p) {
        return getRepository(repoId)
                .flatMap(repo -> {
                    String[] parts = imageReference.split(":");
                    String repository = parts[0];
                    String tag = parts.length > 1 ? parts[1] : "latest";

                    ImagePullResponse response = new ImagePullResponse();
                    response.setImageReference(imageReference);
                    response.setRegistry(repo.getRegistryUrl());
                    response.setRepository(repository);
                    response.setTag(tag);
                    response.setManifestDigest("sha256:" + generateRandomHex(64));
                    response.setStatus("pulling");
                    response.setUsingP2p(useP2p);
                    response.setStartedAt(LocalDateTime.now());
                    response.setDownloadProgress(BigDecimal.ZERO);

                    int layerCount = ThreadLocalRandom.current().nextInt(3, 10);
                    List<LayerInfo> layers = new ArrayList<>();
                    long totalSize = 0;
                    for (int i = 0; i < layerCount; i++) {
                        LayerInfo layer = new LayerInfo();
                        layer.setDigest("sha256:" + generateRandomHex(64));
                        layer.setSizeBytes((long) ThreadLocalRandom.current().nextInt(1024 * 1024, 100 * 1024 * 1024));
                        layer.setMediaType("application/vnd.oci.image.layer.v1.tar+gzip");
                        layer.setStatus("pending");
                        layer.setCached(false);
                        layer.setDownloadedViaP2p(useP2p);
                        layers.add(layer);
                        totalSize += layer.getSizeBytes();

                        persistImageLayer(layer, repoId, useP2p);
                    }
                    response.setLayers(layers);
                    response.setTotalSizeBytes(totalSize);
                    response.setSeedersCount(useP2p ? ThreadLocalRandom.current().nextInt(1, 50) : 0);

                    String pullId = "pull-" + UUID.randomUUID().toString().substring(0, 8);
                    pullProgressCache.put(pullId, response);

                    simulateLayerDownload(pullId, response);

                    return Mono.just(response);
                });
    }

    public Mono<ImagePullResponse> getPullProgress(String pullId) {
        ImagePullResponse response = pullProgressCache.get(pullId);
        if (response == null) {
            return Mono.error(BusinessException.notFound("拉取任务不存在: " + pullId));
        }
        return Mono.just(response);
    }

    @Async
    @Transactional
    public void startSyncAsync(ImageSyncTask task) {
        try {
            task.setStatus("running");
            task.setStartedAt(LocalDateTime.now());
            syncTaskMapper.updateById(task);

            String[] parts = task.getImageReference().split(":");
            int layerCount = ThreadLocalRandom.current().nextInt(3, 10);
            task.setTotalLayers(layerCount);
            task.setCompletedLayers(0);

            for (int i = 0; i < layerCount; i++) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(100, 500));
                task.setCompletedLayers(i + 1);
                task.setProgress(BigDecimal.valueOf(i + 1)
                        .divide(BigDecimal.valueOf(layerCount), 4, BigDecimal.ROUND_HALF_UP));
                syncTaskMapper.updateById(task);
            }

            task.setStatus("completed");
            task.setProgress(BigDecimal.ONE);
            task.setCompletedAt(LocalDateTime.now());
            syncTaskMapper.updateById(task);
            log.info("Completed sync task: {}", task.getTaskId());
        } catch (Exception e) {
            task.setStatus("failed");
            task.setErrorDetail(e.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            syncTaskMapper.updateById(task);
            log.error("Sync task failed: {}", task.getTaskId(), e);
        }
    }

    private void simulateLayerDownload(String pullId, ImagePullResponse response) {
        new Thread(() -> {
            try {
                List<LayerInfo> layers = response.getLayers();
                int totalLayers = layers.size();

                for (int i = 0; i < totalLayers; i++) {
                    LayerInfo layer = layers.get(i);
                    layer.setStatus("downloading");
                    updatePullProgress(pullId, response);

                    Thread.sleep(ThreadLocalRandom.current().nextInt(200, 1000));

                    layer.setStatus("downloaded");
                    layer.setCached(true);
                    response.setDownloadProgress(BigDecimal.valueOf(i + 1)
                            .divide(BigDecimal.valueOf(totalLayers), 4, BigDecimal.ROUND_HALF_UP));
                    updatePullProgress(pullId, response);
                }

                response.setStatus("completed");
                response.setCompletedAt(LocalDateTime.now());
                updatePullProgress(pullId, response);
                log.info("Completed image pull: {}", response.getImageReference());
            } catch (Exception e) {
                response.setStatus("failed");
                response.setCompletedAt(LocalDateTime.now());
                log.error("Image pull failed: {}", response.getImageReference(), e);
            }
        }).start();
    }

    private void updatePullProgress(String pullId, ImagePullResponse response) {
        pullProgressCache.put(pullId, response);
    }

    @Transactional
    public void persistImageLayer(LayerInfo layerInfo, String repoId, boolean useP2p) {
        LambdaQueryWrapper<ImageLayer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImageLayer::getDigest, layerInfo.getDigest());
        ImageLayer existing = layerMapper.selectOne(wrapper);

        if (existing == null) {
            ImageLayer layer = new ImageLayer();
            layer.setLayerId("il-" + UUID.randomUUID().toString().substring(0, 8));
            layer.setDigest(layerInfo.getDigest());
            layer.setSizeBytes(layerInfo.getSizeBytes());
            layer.setMediaType(layerInfo.getMediaType());
            layer.setBlobPath("/data/blobs/" + layerInfo.getDigest().replace(":", "/"));
            layer.setDownloaded(false);
            layer.setP2pSeeders(useP2p ? ThreadLocalRandom.current().nextInt(1, 20) : 0);
            layerMapper.insert(layer);
        } else {
            existing.setP2pSeeders(existing.getP2pSeeders() + (useP2p ? 1 : 0));
            layerMapper.updateById(existing);
        }
    }

    public Flux<ImageLayer> listLayers(String repoId) {
        return Flux.defer(() -> {
            LambdaQueryWrapper<ImageLayer> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ImageLayer::getDownloaded, true)
                    .orderByDesc(ImageLayer::getCreatedAt);
            return Flux.fromIterable(layerMapper.selectList(wrapper));
        });
    }

    public Mono<Map<String, Object>> getP2pStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            LambdaQueryWrapper<ImageLayer> wrapper = new LambdaQueryWrapper<>();
            wrapper.gt(ImageLayer::getP2pSeeders, 0);
            Long p2pLayers = layerMapper.selectCount(wrapper);

            wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ImageLayer::getDownloaded, true);
            Long totalLayers = layerMapper.selectCount(wrapper);

            stats.put("p2pLayers", p2pLayers);
            stats.put("totalCachedLayers", totalLayers);
            stats.put("activeSeeders", ThreadLocalRandom.current().nextInt(10, 100));
            stats.put("bandwidthSavedBytes", ThreadLocalRandom.current().nextLong(1024L * 1024 * 1024, 100L * 1024 * 1024 * 1024));
            return stats;
        });
    }

    private String encryptPassword(String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return "ENC:" + hexString;
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    private String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }
}
