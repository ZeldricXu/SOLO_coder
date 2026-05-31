package com.meshcontrol.image.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.image.dto.ImagePullRequest;
import com.meshcontrol.image.dto.ImageSyncRequest;
import com.meshcontrol.image.dto.RegistryRequest;
import com.meshcontrol.image.entity.ImageManifest;
import com.meshcontrol.image.entity.ImageRegistry;
import com.meshcontrol.image.entity.ImageRepository;
import com.meshcontrol.image.entity.ImageSyncTask;
import com.meshcontrol.image.mapper.ImageManifestMapper;
import com.meshcontrol.image.mapper.ImageRegistryMapper;
import com.meshcontrol.image.mapper.ImageRepositoryMapper;
import com.meshcontrol.image.mapper.ImageSyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDistributionService extends BaseService<ImageRegistryMapper, ImageRegistry> {

    private final ImageRegistryMapper imageRegistryMapper;
    private final ImageRepositoryMapper imageRepositoryMapper;
    private final ImageManifestMapper imageManifestMapper;
    private final ImageSyncTaskMapper imageSyncTaskMapper;

    private final Map<String, ImageSyncTask> runningSyncTasks = new ConcurrentHashMap<>();

    @Transactional
    public ImageRegistry addRegistry(RegistryRequest request) {
        ImageRegistry registry = new ImageRegistry();
        registry.setRegistryId(IdGenerator.generateId("reg"));
        registry.setName(request.getName());
        registry.setUrl(request.getUrl());
        registry.setType(request.getType());
        registry.setAuthType(request.getAuthType());
        registry.setUsername(request.getUsername());
        registry.setPasswordEncrypted(request.getPassword() != null ? encryptPassword(request.getPassword()) : null);
        registry.setTlsEnabled(request.getTlsEnabled());
        registry.setInsecureSkipVerify(request.getInsecureSkipVerify());
        registry.setPriority(request.getPriority());
        registry.setEnabled(request.getEnabled());

        imageRegistryMapper.insert(registry);
        log.info("Image registry added: {} url: {}", registry.getRegistryId(), registry.getUrl());
        return registry;
    }

    public IPage<ImageRegistry> listRegistries(int pageNum, int pageSize) {
        LambdaQueryWrapper<ImageRegistry> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ImageRegistry::getPriority);
        return page(pageNum, pageSize, wrapper);
    }

    public ImageRegistry getRegistry(String registryId) {
        return imageRegistryMapper.selectById(registryId);
    }

    @Transactional
    public boolean deleteRegistry(String registryId) {
        return imageRegistryMapper.deleteById(registryId) > 0;
    }

    @Transactional
    public ImageRepository addRepository(String registryId, String name, String description) {
        ImageRegistry registry = imageRegistryMapper.selectById(registryId);
        if (registry == null) {
            throw new BusinessException("Registry not found");
        }

        ImageRepository repo = new ImageRepository();
        repo.setRepoId(IdGenerator.generateId("repo"));
        repo.setRegistryId(registryId);
        repo.setName(name);
        repo.setDescription(description);
        repo.setSyncEnabled(false);
        repo.setLastSyncAt(null);

        imageRepositoryMapper.insert(repo);
        log.info("Image repository added: {} registry: {}", repo.getRepoId(), registryId);
        return repo;
    }

    public List<ImageRepository> listRepositories(String registryId) {
        if (registryId != null) {
            return imageRepositoryMapper.findByRegistryId(registryId);
        }
        return imageRepositoryMapper.selectList(null);
    }

    public IPage<ImageManifest> listManifests(String repoId, int pageNum, int pageSize) {
        LambdaQueryWrapper<ImageManifest> wrapper = new LambdaQueryWrapper<>();
        if (repoId != null) {
            wrapper.eq(ImageManifest::getRepoId, repoId);
        }
        wrapper.orderByDesc(ImageManifest::getCreatedAt);
        return page(pageNum, pageSize, wrapper);
    }

    @Transactional
    public Map<String, Object> pullImage(ImagePullRequest request) {
        String imageRef = request.getImage() + ":" + request.getTag();
        log.info("Pulling image: {} with P2P: {}", imageRef, request.getP2pEnabled());

        ImageManifest manifest = findOrCreateManifest(request);
        imageManifestMapper.incrementPullCount(manifest.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manifestId", manifest.getManifestId());
        result.put("image", imageRef);
        result.put("digest", manifest.getDigest());
        result.put("layers", manifest.getLayers());
        result.put("totalSize", manifest.getTotalSize());
        result.put("p2pEnabled", request.getP2pEnabled());

        if (request.getP2pEnabled()) {
            result.put("p2pSeedNodes", getP2pSeedNodes(manifest));
            result.put("downloadStrategy", "p2p");
        } else {
            result.put("downloadStrategy", "direct");
        }

        return result;
    }

    private ImageManifest findOrCreateManifest(ImagePullRequest request) {
        List<ImageManifest> existing = imageManifestMapper.selectList(
                new LambdaQueryWrapper<ImageManifest>()
                        .eq(ImageManifest::getTag, request.getTag())
                        .eq(ImageManifest::getDeleted, 0));

        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        ImageManifest manifest = new ImageManifest();
        manifest.setManifestId(IdGenerator.generateId("mf"));
        manifest.setRepoId(request.getRegistryId());
        manifest.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        manifest.setTag(request.getTag());
        manifest.setLayers(generateMockLayers());
        manifest.setTotalSize(125829120L);
        manifest.setArchitecture("amd64");
        manifest.setOs("linux");
        manifest.setP2pEnabled(request.getP2pEnabled());
        manifest.setPullCount(0);

        imageManifestMapper.insert(manifest);
        return manifest;
    }

    private List<Map<String, Object>> generateMockLayers() {
        List<Map<String, Object>> layers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put("digest", "sha256:" + UUID.randomUUID().toString().replace("-", ""));
            layer.put("size", 41943040L);
            layer.put("mediaType", "application/vnd.docker.image.rootfs.diff.tar.gzip");
            layers.add(layer);
        }
        return layers;
    }

    private List<String> getP2pSeedNodes(ImageManifest manifest) {
        if (manifest.getP2pSeedNodes() != null && !manifest.getP2pSeedNodes().isEmpty()) {
            return manifest.getP2pSeedNodes();
        }
        return Arrays.asList("p2p-seed-1.local:6881", "p2p-seed-2.local:6881");
    }

    @Transactional
    public ImageSyncTask startSync(ImageSyncRequest request) {
        ImageRegistry source = imageRegistryMapper.selectById(request.getSourceRegistryId());
        ImageRegistry target = imageRegistryMapper.selectById(request.getTargetRegistryId());
        if (source == null || target == null) {
            throw new BusinessException("Source or target registry not found");
        }

        ImageSyncTask task = new ImageSyncTask();
        task.setTaskId(IdGenerator.generateId("sync"));
        task.setSourceRegistryId(request.getSourceRegistryId());
        task.setTargetRegistryId(request.getTargetRegistryId());
        task.setSourceRepo(request.getSourceRepo());
        task.setTargetRepo(request.getTargetRepo());
        task.setTagFilter(request.getTagFilter());
        task.setStatus("pending");
        task.setProgress(0.0);
        task.setTotalImages(0);
        task.setSyncedImages(0);
        task.setStartedAt(LocalDateTime.now());

        imageSyncTaskMapper.insert(task);
        runningSyncTasks.put(task.getTaskId(), task);

        executeSyncAsync(task);
        log.info("Image sync task started: {} {} -> {}",
                task.getTaskId(), request.getSourceRepo(), request.getTargetRepo());
        return task;
    }

    @Async
    @Transactional
    public void executeSyncAsync(ImageSyncTask task) {
        try {
            task.setStatus("running");
            imageSyncTaskMapper.updateById(task);

            List<String> tags = discoverImages(task);
            task.setTotalImages(tags.size());

            int synced = 0;
            for (String tag : tags) {
                if (!runningSyncTasks.containsKey(task.getTaskId())) {
                    break;
                }
                syncImage(task, tag);
                synced++;
                double progress = (double) synced / tags.size() * 100;
                imageSyncTaskMapper.updateProgress(task.getTaskId(), progress, synced);
                Thread.sleep(100);
            }

            task.setStatus("completed");
            task.setProgress(100.0);
            task.setSyncedImages(synced);
            task.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            task.setStatus("failed");
            task.setErrorDetail(e.getMessage());
            log.error("Sync task failed: {}", task.getTaskId(), e);
        } finally {
            imageSyncTaskMapper.updateById(task);
            runningSyncTasks.remove(task.getTaskId());
        }
    }

    private List<String> discoverImages(ImageSyncTask task) {
        return Arrays.asList("v1.0.0", "v1.1.0", "v1.2.0", "latest");
    }

    private void syncImage(ImageSyncTask task, String tag) {
        log.debug("Syncing image {}:{} from {} to {}",
                task.getSourceRepo(), tag, task.getSourceRegistryId(), task.getTargetRegistryId());
    }

    public ImageSyncTask getSyncTask(String taskId) {
        return imageSyncTaskMapper.selectById(taskId);
    }

    public List<ImageSyncTask> listSyncTasks(String status) {
        if (status != null) {
            return imageSyncTaskMapper.findByStatus(status);
        }
        return imageSyncTaskMapper.selectList(null);
    }

    public boolean cancelSync(String taskId) {
        ImageSyncTask task = runningSyncTasks.remove(taskId);
        if (task != null) {
            task.setStatus("cancelled");
            imageSyncTaskMapper.updateById(task);
            return true;
        }
        return false;
    }

    @Scheduled(fixedRate = 3600000)
    public void scheduledSync() {
        List<ImageRepository> repos = imageRepositoryMapper.findSyncEnabled();
        for (ImageRepository repo : repos) {
            log.info("Scheduled sync for repository: {}", repo.getName());
        }
    }

    public Map<String, Object> getDistributionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRegistries", imageRegistryMapper.selectCount(null));
        stats.put("totalRepositories", imageRepositoryMapper.selectCount(null));
        stats.put("totalManifests", imageManifestMapper.selectCount(null));
        stats.put("runningSyncTasks", runningSyncTasks.size());
        return stats;
    }

    private String encryptPassword(String password) {
        return Base64.getEncoder().encodeToString(password.getBytes());
    }

    public Map<String, Object> enableP2p(String manifestId, List<String> seedNodes) {
        ImageManifest manifest = imageManifestMapper.selectById(manifestId);
        if (manifest == null) {
            throw new BusinessException("Manifest not found");
        }
        manifest.setP2pEnabled(true);
        manifest.setP2pSeedNodes(seedNodes);
        imageManifestMapper.updateById(manifest);

        Map<String, Object> result = new HashMap<>();
        result.put("manifestId", manifestId);
        result.put("p2pEnabled", true);
        result.put("seedNodes", seedNodes);
        return result;
    }
}
