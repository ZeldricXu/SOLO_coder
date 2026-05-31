package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.dto.ChunkCreateDTO;
import com.modelguard.dto.DocumentPipelineDTO;
import com.modelguard.dto.DocumentTaskDTO;
import com.modelguard.entity.DocumentChunk;
import com.modelguard.entity.DocumentPipeline;
import com.modelguard.entity.DocumentTask;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.DocumentChunkMapper;
import com.modelguard.mapper.DocumentPipelineMapper;
import com.modelguard.mapper.DocumentTaskMapper;
import com.modelguard.service.DocumentService;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentPipelineMapper pipelineMapper;
    private final DocumentTaskMapper taskMapper;
    private final DocumentChunkMapper chunkMapper;

    private static final List<String> SENTENCE_SEPARATORS = Arrays.asList("[。！？.!?]", "[，,;；]", "[\n\r]", "[ 　]");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentPipeline> createPipeline(DocumentPipelineDTO dto) {
        return Mono.fromCallable(() -> {
            DocumentPipeline pipeline = new DocumentPipeline();
            pipeline.setPipelineId("pipe_" + IdUtil.simpleUUID());
            pipeline.setName(dto.getName());
            pipeline.setDescription(dto.getDescription());
            pipeline.setSourceType(dto.getSourceType());
            pipeline.setChunkSize(dto.getChunkSize());
            pipeline.setChunkOverlap(dto.getChunkOverlap());
            pipeline.setEmbeddingModel(dto.getEmbeddingModel());
            pipeline.setVectorDimension(dto.getVectorDimension());
            pipeline.setStatus("ACTIVE");
            pipeline.setCreatedBy(dto.getCreatedBy());

            pipelineMapper.insert(pipeline);
            log.info("Created document pipeline: pipelineId={}", pipeline.getPipelineId());
            return pipeline;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<DocumentPipeline> getPipeline(String pipelineId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DocumentPipeline> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentPipeline::getPipelineId, pipelineId);
            DocumentPipeline pipeline = pipelineMapper.selectOne(wrapper);
            if (pipeline == null) {
                throw new ResourceNotFoundException("DocumentPipeline", pipelineId);
            }
            return pipeline;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PageResult<DocumentPipeline>> pagePipelines(String status, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<DocumentPipeline> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<DocumentPipeline> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DocumentPipeline::getStatus, status);
            }
            wrapper.orderByDesc(DocumentPipeline::getCreatedAt);
            Page<DocumentPipeline> result = pipelineMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentPipeline> updatePipeline(String pipelineId, DocumentPipelineDTO dto) {
        return getPipeline(pipelineId)
                .flatMap(pipeline -> Mono.fromCallable(() -> {
                    pipeline.setName(dto.getName());
                    pipeline.setDescription(dto.getDescription());
                    pipeline.setSourceType(dto.getSourceType());
                    pipeline.setChunkSize(dto.getChunkSize());
                    pipeline.setChunkOverlap(dto.getChunkOverlap());
                    pipeline.setEmbeddingModel(dto.getEmbeddingModel());
                    pipeline.setVectorDimension(dto.getVectorDimension());
                    pipelineMapper.updateById(pipeline);
                    return pipeline;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deletePipeline(String pipelineId) {
        return getPipeline(pipelineId)
                .flatMap(pipeline -> Mono.fromCallable(() -> {
                    pipelineMapper.deleteById(pipeline.getId());
                    return null;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTask> submitTask(DocumentTaskDTO dto) {
        return getPipeline(dto.getPipelineId())
                .flatMap(pipeline -> Mono.fromCallable(() -> {
                    DocumentTask task = new DocumentTask();
                    task.setTaskId("doctask_" + IdUtil.simpleUUID());
                    task.setPipelineId(dto.getPipelineId());
                    task.setFileName(dto.getFileName());
                    task.setFilePath(dto.getFilePath());
                    task.setFileSize(dto.getFileSize());
                    task.setStatus("PENDING");
                    task.setPhase("INIT");
                    task.setProgress(BigDecimal.ZERO);
                    task.setVectorStore(dto.getVectorStore());
                    task.setStartedAt(LocalDateTime.now());

                    taskMapper.insert(task);
                    log.info("Submitted document task: taskId={}, pipelineId={}", task.getTaskId(), dto.getPipelineId());
                    return task;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<DocumentTask> getTask(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentTask::getTaskId, taskId);
            DocumentTask task = taskMapper.selectOne(wrapper);
            if (task == null) {
                throw new ResourceNotFoundException("DocumentTask", taskId);
            }
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTask> updateTaskStatus(String taskId, String status, String phase, Double progress) {
        return getTask(taskId)
                .flatMap(task -> Mono.fromCallable(() -> {
                    if (status != null) {
                        task.setStatus(status);
                    }
                    if (phase != null) {
                        task.setPhase(phase);
                    }
                    if (progress != null) {
                        task.setProgress(BigDecimal.valueOf(progress));
                    }
                    taskMapper.updateById(task);
                    return task;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTask> markTaskFailed(String taskId, String errorDetail) {
        return getTask(taskId)
                .flatMap(task -> Mono.fromCallable(() -> {
                    task.setStatus("FAILED");
                    task.setErrorDetail(errorDetail);
                    task.setCompletedAt(LocalDateTime.now());
                    taskMapper.updateById(task);
                    log.error("Document task failed: taskId={}, error={}", taskId, errorDetail);
                    return task;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTask> markTaskCompleted(String taskId, int totalChunks) {
        return getTask(taskId)
                .flatMap(task -> Mono.fromCallable(() -> {
                    task.setStatus("COMPLETED");
                    task.setPhase("DONE");
                    task.setProgress(BigDecimal.ONE);
                    task.setTotalChunks(totalChunks);
                    task.setCompletedAt(LocalDateTime.now());
                    taskMapper.updateById(task);
                    log.info("Document task completed: taskId={}, chunks={}", taskId, totalChunks);
                    return task;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<PageResult<DocumentTask>> pageTasks(String pipelineId, String status, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<DocumentTask> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
            if (pipelineId != null && !pipelineId.isEmpty()) {
                wrapper.eq(DocumentTask::getPipelineId, pipelineId);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DocumentTask::getStatus, status);
            }
            wrapper.orderByDesc(DocumentTask::getCreatedAt);
            Page<DocumentTask> result = taskMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentChunk> createChunk(ChunkCreateDTO dto) {
        return getTask(dto.getTaskId())
                .flatMap(task -> Mono.fromCallable(() -> {
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setChunkId("chunk_" + IdUtil.simpleUUID());
                    chunk.setTaskId(dto.getTaskId());
                    chunk.setContent(dto.getContent());
                    chunk.setMetadata(dto.getMetadata());
                    chunk.setEmbedding(dto.getEmbedding());
                    chunk.setPageNumber(dto.getPageNumber());
                    chunk.setStartIndex(dto.getStartIndex());
                    chunk.setEndIndex(dto.getEndIndex());

                    chunkMapper.insert(chunk);
                    return chunk;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<List<DocumentChunk>> batchCreateChunks(List<ChunkCreateDTO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        String taskId = chunks.get(0).getTaskId();
        return getTask(taskId)
                .flatMap(task -> Mono.fromCallable(() -> {
                    List<DocumentChunk> result = new ArrayList<>();
                    for (ChunkCreateDTO dto : chunks) {
                        DocumentChunk chunk = new DocumentChunk();
                        chunk.setChunkId("chunk_" + IdUtil.simpleUUID());
                        chunk.setTaskId(dto.getTaskId());
                        chunk.setContent(dto.getContent());
                        chunk.setMetadata(dto.getMetadata());
                        chunk.setEmbedding(dto.getEmbedding());
                        chunk.setPageNumber(dto.getPageNumber());
                        chunk.setStartIndex(dto.getStartIndex());
                        chunk.setEndIndex(dto.getEndIndex());
                        chunkMapper.insert(chunk);
                        result.add(chunk);
                    }
                    log.info("Batch created {} chunks for task {}", result.size(), taskId);
                    return result;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<List<DocumentChunk>> getTaskChunks(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getTaskId, taskId)
                    .orderByAsc(DocumentChunk::getStartIndex);
            return chunkMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PageResult<DocumentChunk>> pageTaskChunks(String taskId, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<DocumentChunk> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getTaskId, taskId)
                    .orderByAsc(DocumentChunk::getStartIndex);
            Page<DocumentChunk> result = chunkMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<String>> smartSplit(String content, int chunkSize, int chunkOverlap, String separator) {
        return Mono.fromCallable(() -> {
            if (content == null || content.isEmpty()) {
                return Collections.emptyList();
            }
            if (chunkSize <= 0) {
                chunkSize = 512;
            }
            if (chunkOverlap < 0) {
                chunkOverlap = 0;
            }
            if (chunkOverlap >= chunkSize) {
                throw new BusinessException("分块重叠不能大于等于分块大小");
            }

            List<String> sentences = splitBySentence(content, separator);
            List<String> chunks = new ArrayList<>();
            StringBuilder currentChunk = new StringBuilder();
            List<String> currentSentences = new ArrayList<>();

            for (String sentence : sentences) {
                if (currentChunk.length() + sentence.length() > chunkSize && currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());

                    int overlapCount = 0;
                    int overlapLength = 0;
                    List<String> overlapSentences = new ArrayList<>();
                    for (int i = currentSentences.size() - 1; i >= 0 && overlapLength < chunkOverlap; i--) {
                        String s = currentSentences.get(i);
                        overlapSentences.add(0, s);
                        overlapLength += s.length();
                    }
                    currentChunk = new StringBuilder();
                    for (String s : overlapSentences) {
                        currentChunk.append(s);
                    }
                    currentSentences = new ArrayList<>(overlapSentences);
                }
                currentChunk.append(sentence);
                currentSentences.add(sentence);
            }

            if (currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
            }

            log.debug("Smart split completed: input length={}, chunkSize={}, chunks={}",
                    content.length(), chunkSize, chunks.size());
            return chunks;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private List<String> splitBySentence(String content, String customSeparator) {
        List<String> separators = new ArrayList<>();
        if (customSeparator != null && !customSeparator.isEmpty()) {
            separators.add(customSeparator);
        } else {
            separators.addAll(SENTENCE_SEPARATORS);
        }

        List<String> parts = Collections.singletonList(content);
        for (String sep : separators) {
            Pattern pattern = Pattern.compile(sep);
            List<String> newParts = new ArrayList<>();
            for (String part : parts) {
                String[] split = pattern.split(part);
                for (int i = 0; i < split.length; i++) {
                    if (!split[i].isEmpty()) {
                        newParts.add(split[i]);
                    }
                }
            }
            if (!newParts.isEmpty()) {
                parts = newParts;
            }
        }
        return parts.stream().filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
    }

    @Override
    public Mono<Map<String, Object>> getTaskProgress(String taskId) {
        return Mono.zip(getTask(taskId), getTaskChunks(taskId))
                .map(tuple -> {
                    DocumentTask task = tuple.getT1();
                    List<DocumentChunk> chunks = tuple.getT2();

                    Map<String, Object> progress = new LinkedHashMap<>();
                    progress.put("taskId", task.getTaskId());
                    progress.put("status", task.getStatus());
                    progress.put("phase", task.getPhase());
                    progress.put("progress", task.getProgress());
                    progress.put("totalChunks", task.getTotalChunks());
                    progress.put("processedChunks", chunks.size());
                    progress.put("startedAt", task.getStartedAt());
                    progress.put("completedAt", task.getCompletedAt());
                    progress.put("errorDetail", task.getErrorDetail());

                    if (task.getStartedAt() != null) {
                        long duration;
                        if (task.getCompletedAt() != null) {
                            duration = java.time.Duration.between(task.getStartedAt(), task.getCompletedAt()).getSeconds();
                        } else {
                            duration = java.time.Duration.between(task.getStartedAt(), LocalDateTime.now()).getSeconds();
                        }
                        progress.put("durationSeconds", duration);
                    }

                    return progress;
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> retryTask(String taskId) {
        return getTask(taskId)
                .flatMap(task -> {
                    if (!"FAILED".equals(task.getStatus())) {
                        throw new BusinessException("只有失败状态的任务可以重试");
                    }

                    LambdaQueryWrapper<DocumentChunk> chunkWrapper = new LambdaQueryWrapper<>();
                    chunkWrapper.eq(DocumentChunk::getTaskId, taskId);

                    return Mono.fromCallable(() -> {
                        chunkMapper.delete(chunkWrapper);

                        task.setStatus("PENDING");
                        task.setPhase("INIT");
                        task.setProgress(BigDecimal.ZERO);
                        task.setErrorDetail(null);
                        task.setTotalChunks(null);
                        task.setStartedAt(LocalDateTime.now());
                        task.setCompletedAt(null);
                        taskMapper.updateById(task);

                        log.info("Retrying document task: taskId={}", taskId);
                        return null;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }
}
