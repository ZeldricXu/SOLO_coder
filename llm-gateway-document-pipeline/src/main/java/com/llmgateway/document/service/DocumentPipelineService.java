package com.llmgateway.document.service;

import cn.hutool.core.util.StrUtil;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.document.dto.ParseConfigDTO;
import com.llmgateway.document.entity.Document;
import com.llmgateway.document.entity.DocumentChunk;
import com.llmgateway.document.entity.DocumentEmbedding;
import com.llmgateway.document.entity.ParseTask;
import com.llmgateway.document.mapper.DocumentChunkMapper;
import com.llmgateway.document.mapper.DocumentEmbeddingMapper;
import com.llmgateway.document.mapper.ParseTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPipelineService {

    private final DocumentService documentService;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentEmbeddingMapper embeddingMapper;
    private final ParseTaskMapper taskMapper;

    @Value("${document.parse.timeout-seconds:300}")
    private int parseTimeoutSeconds;

    @Value("${document.parse.max-content-size:10485760}")
    private int maxContentSize;

    @Value("${document.parse.max-chunks:10000}")
    private int maxChunks;

    private static final ExecutorService PARSE_EXECUTOR = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "doc-parse-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @Transactional(rollbackFor = Exception.class)
    public ParseTask createParseTask(String documentId, ParseConfigDTO config) {
        Document document = documentService.getById(documentId);

        if (document.getContent() != null && document.getContent().length() > maxContentSize) {
            throw new BusinessException(
                    String.format("文档内容过大，最大支持 %d 字节，当前 %d 字节",
                            maxContentSize, document.getContent().length())
            );
        }

        ParseTask task = new ParseTask();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setDocumentId(documentId);
        task.setPipelineId(config.getPipelineId());
        task.setStatus(CommonConstants.STATUS_PENDING);
        task.setPhase("initializing");
        task.setProgress(0.0);
        task.setChunkCount(0);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.insert(task);

        log.info("解析任务创建成功: taskId={}, documentId={}", task.getTaskId(), documentId);
        return task;
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void executeParse(String taskId, String content, ParseConfigDTO config) {
        ParseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("解析任务不存在: taskId={}", taskId);
            return;
        }

        if (!CommonConstants.STATUS_PENDING.equals(task.getStatus())) {
            log.warn("解析任务状态异常，跳过执行: taskId={}, currentStatus={}",
                    taskId, task.getStatus());
            return;
        }

        task.setStatus(CommonConstants.STATUS_RUNNING);
        task.setPhase("parsing");
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Future<?> parseFuture = PARSE_EXECUTOR.submit(() -> {
            try {
                doParse(task, content, config, cancelled);
            } catch (Exception e) {
                log.error("文档解析执行异常: taskId={}", taskId, e);
                markTaskFailed(task, e.getMessage());
            }
        });

        try {
            parseFuture.get(parseTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancelled.set(true);
            parseFuture.cancel(true);
            log.error("文档解析超时，已取消任务: taskId={}, timeout={}s", taskId, parseTimeoutSeconds);
            markTaskFailed(task, String.format("解析超时，超过 %d 秒限制", parseTimeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            parseFuture.cancel(true);
            log.warn("文档解析被中断: taskId={}", taskId);
            markTaskFailed(task, "任务执行被中断");
        } catch (ExecutionException e) {
            log.error("文档解析执行失败: taskId={}", taskId, e.getCause());
            markTaskFailed(task, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    private void doParse(ParseTask task, String content, ParseConfigDTO config, AtomicBoolean cancelled) {
        String taskId = task.getTaskId();
        String documentId = task.getDocumentId();

        try {
            if (cancelled.get()) {
                return;
            }

            task.setProgress(0.1);
            taskMapper.updateById(task);

            List<DocumentChunk> chunks = splitDocument(content, config, documentId);

            if (chunks.size() > maxChunks) {
                throw new BusinessException(
                        String.format("文档切片数量过多，最大支持 %d 个，当前 %d 个", maxChunks, chunks.size())
                );
            }

            if (cancelled.get()) {
                return;
            }

            task.setPhase("chunking");
            task.setProgress(0.5);
            task.setChunkCount(chunks.size());
            taskMapper.updateById(task);

            for (DocumentChunk chunk : chunks) {
                if (cancelled.get()) {
                    return;
                }
                chunkMapper.insert(chunk);
            }

            if (config.getEnableEmbedding()) {
                if (cancelled.get()) {
                    return;
                }

                task.setPhase("embedding");
                task.setProgress(0.7);
                taskMapper.updateById(task);

                for (DocumentChunk chunk : chunks) {
                    if (cancelled.get()) {
                        return;
                    }
                    DocumentEmbedding embedding = generateEmbedding(chunk, config);
                    embeddingMapper.insert(embedding);
                }
            }

            if (cancelled.get()) {
                return;
            }

            task.setStatus(CommonConstants.STATUS_SUCCESS);
            task.setPhase("completed");
            task.setProgress(1.0);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            documentService.updateStatus(documentId, CommonConstants.STATUS_SUCCESS);

            log.info("文档解析完成: taskId={}, documentId={}, chunkCount={}",
                    taskId, documentId, chunks.size());
        } catch (Exception e) {
            log.error("文档解析失败: taskId={}, documentId={}", taskId, documentId, e);
            markTaskFailed(task, e.getMessage());
        }
    }

    private void markTaskFailed(ParseTask task, String errorDetail) {
        try {
            task.setStatus(CommonConstants.STATUS_FAILED);
            task.setErrorDetail(errorDetail);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            documentService.updateStatus(task.getDocumentId(), CommonConstants.STATUS_FAILED);
        } catch (Exception ex) {
            log.error("标记任务失败时出错: taskId={}", task.getTaskId(), ex);
        }
    }

    private List<DocumentChunk> splitDocument(String content, ParseConfigDTO config, String documentId) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (StrUtil.isBlank(content)) {
            return chunks;
        }

        int chunkSize = config.getChunkSize();
        int overlap = config.getChunkOverlap();
        String separator = config.getSeparator();

        if (chunkSize <= overlap) {
            throw new BusinessException("chunkSize必须大于chunkOverlap");
        }

        String[] parts = content.split(separator);
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        int startOffset = 0;

        for (String part : parts) {
            if (currentChunk.length() + part.length() > chunkSize && currentChunk.length() > 0) {
                DocumentChunk chunk = createChunk(currentChunk.toString(), chunkIndex++, startOffset, config, documentId);
                chunks.add(chunk);
                startOffset += Math.max(0, currentChunk.length() - overlap);
                currentChunk = new StringBuilder(currentChunk.substring(Math.max(0, currentChunk.length() - overlap)));
            }
            currentChunk.append(part).append(separator);
        }

        if (currentChunk.length() > 0) {
            DocumentChunk chunk = createChunk(currentChunk.toString(), chunkIndex, startOffset, config, documentId);
            chunks.add(chunk);
        }

        return chunks;
    }

    private DocumentChunk createChunk(String content, int index, int startOffset, ParseConfigDTO config, String documentId) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(IdGenerator.generateId("chunk"));
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setContentLength(content.length());
        chunk.setTokenCount(estimateTokenCount(content));
        chunk.setStartOffset(startOffset);
        chunk.setEndOffset(startOffset + content.length());
        return chunk;
    }

    private int estimateTokenCount(String text) {
        return text.length() / 4;
    }

    private DocumentEmbedding generateEmbedding(DocumentChunk chunk, ParseConfigDTO config) {
        DocumentEmbedding embedding = new DocumentEmbedding();
        embedding.setEmbeddingId(IdGenerator.generateId("embed"));
        embedding.setChunkId(chunk.getChunkId());
        embedding.setDocumentId(chunk.getDocumentId());
        embedding.setModelName(config.getEmbeddingModel());
        embedding.setVector(chunk.getContent().getBytes(StandardCharsets.UTF_8));
        embedding.setDimension(1536);
        return embedding;
    }

    public ParseTask getTask(String taskId) {
        ParseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "解析任务不存在");
        }
        return task;
    }

    public List<DocumentChunk> getDocumentChunks(String documentId) {
        return chunkMapper.selectByDocumentId(documentId);
    }
}
