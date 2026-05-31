package com.modelguard.service.document.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.DocumentChunkCreateRequest;
import com.modelguard.dto.response.DocumentChunkResponse;
import com.modelguard.entity.DocumentChunk;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.DocumentChunkMapper;
import com.modelguard.service.document.DocumentChunkService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import com.modelguard.util.TextSplitUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChunkServiceImpl implements DocumentChunkService {

    private final DocumentChunkMapper documentChunkMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentChunkResponse> createChunk(DocumentChunkCreateRequest request) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            DocumentChunk chunk = EntityConverter.toEntity(request);
            chunk.setChunkId(IdGeneratorUtil.generateChunkId());

            documentChunkMapper.insert(chunk);
            log.debug("Created document chunk: chunkId={}, taskId={}", chunk.getChunkId(), request.getTaskId());
            return EntityConverter.toResponse(chunk);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<List<DocumentChunkResponse>> createChunks(List<DocumentChunkCreateRequest> requests) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            List<DocumentChunkResponse> responses = new ArrayList<>();
            for (DocumentChunkCreateRequest request : requests) {
                DocumentChunk chunk = EntityConverter.toEntity(request);
                chunk.setChunkId(IdGeneratorUtil.generateChunkId());
                documentChunkMapper.insert(chunk);
                responses.add(EntityConverter.toResponse(chunk));
            }
            log.info("Created {} document chunks", responses.size());
            return responses;
        });
    }

    @Override
    public Mono<DocumentChunkResponse> getChunk(String chunkId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getChunkId, chunkId);
            DocumentChunk chunk = documentChunkMapper.selectOne(wrapper);
            if (chunk == null) {
                throw new ResourceNotFoundException("DocumentChunk", chunkId);
            }
            return EntityConverter.toResponse(chunk);
        });
    }

    @Override
    public Mono<List<DocumentChunkResponse>> listChunksByTask(String taskId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getTaskId, taskId)
                    .orderByAsc(DocumentChunk::getChunkIndex);
            return documentChunkMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<PageResult<DocumentChunkResponse>> pageChunksByTask(String taskId, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Page<DocumentChunk> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getTaskId, taskId)
                    .orderByAsc(DocumentChunk::getChunkIndex);
            Page<DocumentChunk> result = documentChunkMapper.selectPage(page, wrapper);

            List<DocumentChunkResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    public Mono<List<DocumentChunkResponse>> searchChunks(String pipelineId, String keyword) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getPipelineId, pipelineId)
                    .like(DocumentChunk::getContent, keyword)
                    .orderByDesc(DocumentChunk::getCreatedAt)
                    .last("LIMIT 100");
            return documentChunkMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> deleteChunksByTask(String taskId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getTaskId, taskId);
            int deleted = documentChunkMapper.delete(wrapper);
            log.info("Deleted {} document chunks for taskId={}", deleted, taskId);
            return deleted > 0;
        });
    }

    @Override
    public Mono<Integer> countChunksByTask(String taskId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentChunk::getTaskId, taskId);
            return Math.toIntExact(documentChunkMapper.selectCount(wrapper));
        });
    }

    @Override
    public Mono<List<String>> smartSplitDocument(String content, int chunkSize, int overlapSize) {
        return Mono.fromCallable(() -> TextSplitUtil.smartSplit(content, chunkSize, overlapSize));
    }
}
