package com.enterprise.risk.model;

import com.enterprise.risk.common.exception.RiskException;
import com.enterprise.risk.common.model.ModelConfig;
import com.microsoft.onnxruntime.OrtEnvironment;
import com.microsoft.onnxruntime.OrtException;
import com.microsoft.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Component
public class OnnxModelLoader {

    private final OrtEnvironment environment;

    private final Map<String, ModelSessionHolder> sessionCache = new ConcurrentHashMap<>();

    private static final int SESSION_OPTIONS_OPTIMIZATION_LEVEL = 1;

    private static final String TEMP_MODEL_PREFIX = "risk_model_";

    public OnnxModelLoader() {
        this.environment = OrtEnvironment.getEnvironment();
        log.info("ONNX Runtime Environment 初始化完成");
    }

    public OrtSession loadModel(ModelConfig config) {
        String modelId = config.getModelId();
        ModelSessionHolder holder = sessionCache.get(modelId);

        if (holder != null && holder.isValid(config)) {
            log.debug("模型 [{}] 已加载，复用现有Session", modelId);
            return holder.getSession();
        }

        return createOrReplaceSession(config);
    }

    private OrtSession createOrReplaceSession(ModelConfig config) {
        String modelId = config.getModelId();
        ReentrantReadWriteLock.WriteLock writeLock = null;
        ModelSessionHolder oldHolder = sessionCache.get(modelId);

        if (oldHolder != null) {
            writeLock = oldHolder.getLock().writeLock();
            writeLock.lock();
        }

        try {
            Path modelPath = resolveModelPath(config.getModelPath());
            OrtSession.SessionOptions options = createSessionOptions();
            OrtSession session = environment.createSession(modelPath.toString(), options);

            ModelSessionHolder newHolder = new ModelSessionHolder(
                    session,
                    config.getModelVersion(),
                    config.getUpdatedAt()
            );

            ModelSessionHolder previous = sessionCache.put(modelId, newHolder);

            if (previous != null) {
                previous.close();
                log.info("模型 [{}] 热更新完成: 旧版本已卸载", modelId);
            }

            log.info("模型 [{}] 加载成功: 版本={}, 路径={}",
                    modelId, config.getModelVersion(), modelPath);

            return session;

        } catch (OrtException e) {
            throw new RiskException("ONNX模型加载失败: " + modelId, e);
        } catch (IOException e) {
            throw new RiskException("模型文件读取失败: " + config.getModelPath(), e);
        } finally {
            if (writeLock != null) {
                writeLock.unlock();
            }
        }
    }

    private Path resolveModelPath(String modelPath) throws IOException {
        if (modelPath.startsWith("classpath:")) {
            String resourcePath = modelPath.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(resourcePath);

            if (!resource.exists()) {
                throw new IOException("Classpath资源不存在: " + resourcePath);
            }

            Path tempFile = Files.createTempFile(TEMP_MODEL_PREFIX, ".onnx");
            tempFile.toFile().deleteOnExit();

            try (InputStream is = resource.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }

            log.debug("Classpath模型已解压到临时文件: {}", tempFile);
            return tempFile;
        }

        Path path = Paths.get(modelPath);
        if (!Files.exists(path)) {
            throw new IOException("模型文件不存在: " + modelPath);
        }
        return path;
    }

    private OrtSession.SessionOptions createSessionOptions() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        options.setInterOpNumThreads(2);
        return options;
    }

    public void unloadModel(String modelId) {
        ModelSessionHolder holder = sessionCache.remove(modelId);
        if (holder != null) {
            holder.close();
            log.info("模型 [{}] 已卸载", modelId);
        }
    }

    public boolean isModelLoaded(String modelId) {
        ModelSessionHolder holder = sessionCache.get(modelId);
        return holder != null && !holder.isClosed();
    }

    public void hotReload(ModelConfig config) {
        log.info("开始热更新模型 [{}]", config.getModelId());
        createOrReplaceSession(config);
    }

    @PreDestroy
    public void shutdown() {
        log.info("开始关闭ONNX模型加载器，共 {} 个模型待卸载", sessionCache.size());

        for (Map.Entry<String, ModelSessionHolder> entry : sessionCache.entrySet()) {
            try {
                entry.getValue().close();
                log.debug("已卸载模型: {}", entry.getKey());
            } catch (Exception e) {
                log.warn("卸载模型 [{}] 时发生异常", entry.getKey(), e);
            }
        }
        sessionCache.clear();

        try {
            environment.close();
            log.info("ONNX Runtime Environment 已关闭");
        } catch (Exception e) {
            log.warn("关闭ONNX Environment时发生异常", e);
        }
    }

    public int getLoadedModelCount() {
        return (int) sessionCache.values().stream()
                .filter(h -> !h.isClosed())
                .count();
    }

    private static class ModelSessionHolder {
        private final AtomicReference<OrtSession> sessionRef;
        private final String version;
        private final Long lastUpdateTime;
        private final ReentrantReadWriteLock lock;
        private volatile boolean closed;

        ModelSessionHolder(OrtSession session, String version, Long lastUpdateTime) {
            this.sessionRef = new AtomicReference<>(session);
            this.version = version;
            this.lastUpdateTime = lastUpdateTime;
            this.lock = new ReentrantReadWriteLock();
            this.closed = false;
        }

        OrtSession getSession() {
            ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
            readLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("模型Session已关闭");
                }
                return sessionRef.get();
            } finally {
                readLock.unlock();
            }
        }

        boolean isValid(ModelConfig config) {
            if (closed) {
                return false;
            }
            if (config.getModelVersion() != null
                    && !config.getModelVersion().equals(this.version)) {
                return false;
            }
            return config.getUpdatedAt() == null
                    || config.getUpdatedAt() <= this.lastUpdateTime;
        }

        ReentrantReadWriteLock getLock() {
            return lock;
        }

        boolean isClosed() {
            return closed;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            OrtSession session = sessionRef.getAndSet(null);
            if (session != null) {
                try {
                    session.close();
                } catch (OrtException e) {
                    log.warn("关闭ONNX Session时发生异常", e);
                }
            }
        }
    }
}
