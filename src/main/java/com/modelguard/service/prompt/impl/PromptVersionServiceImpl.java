package com.modelguard.service.prompt.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.PromptVersionCreateRequest;
import com.modelguard.dto.response.PromptVersionResponse;
import com.modelguard.entity.PromptVersion;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.PromptVersionMapper;
import com.modelguard.service.prompt.PromptVersionService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import com.modelguard.util.TextSplitUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptVersionServiceImpl implements PromptVersionService {

    private final PromptVersionMapper promptVersionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<PromptVersionResponse> createPromptVersion(PromptVersionCreateRequest request) {
        return getNextVersion(request.getPromptId())
                .flatMap(nextVersion -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    String promptId = request.getPromptId() != null ? request.getPromptId() : IdGeneratorUtil.generatePromptId();

                    PromptVersion version = EntityConverter.toEntity(request);
                    version.setPromptId(promptId);
                    version.setVersion(nextVersion);

                    promptVersionMapper.insert(version);
                    log.info("Created prompt version: promptId={}, version={}", promptId, nextVersion);
                    return EntityConverter.toResponse(version);
                }));
    }

    @Override
    public Mono<PromptVersionResponse> getPromptVersion(String promptId, Integer version) {
        return getPromptVersionEntity(promptId, version)
                .map(EntityConverter::toResponse);
    }

    @Override
    public Mono<PromptVersion> getPromptVersionEntity(String promptId, Integer version) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .eq(PromptVersion::getVersion, version);
            PromptVersion pv = promptVersionMapper.selectOne(wrapper);
            if (pv == null) {
                throw new ResourceNotFoundException("PromptVersion", promptId + " v" + version);
            }
            return pv;
        });
    }

    @Override
    public Mono<List<PromptVersionResponse>> listPromptVersions(String promptId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion);
            return promptVersionMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<PageResult<PromptVersionResponse>> pagePromptVersions(String promptId, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Page<PromptVersion> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getCreatedAt);
            Page<PromptVersion> result = promptVersionMapper.selectPage(page, wrapper);

            List<PromptVersionResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    public Mono<PromptVersionResponse> getLatestPromptVersion(String promptId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion)
                    .last("LIMIT 1");
            PromptVersion latest = promptVersionMapper.selectOne(wrapper);
            if (latest == null) {
                throw new ResourceNotFoundException("PromptVersion", promptId);
            }
            return EntityConverter.toResponse(latest);
        });
    }

    @Override
    public Mono<String> renderPrompt(String promptId, Integer version, Map<String, Object> variables) {
        Integer targetVersion = version;
        return (targetVersion != null ? getPromptVersionEntity(promptId, targetVersion) :
                getLatestPromptVersion(promptId).map(r -> {
                    PromptVersion pv = new PromptVersion();
                    pv.setContent(r.getContent());
                    pv.setVariables(r.getVariables());
                    return pv;
                }))
                .map(pv -> TextSplitUtil.renderTemplate(pv.getContent(), variables));
    }

    @Override
    public Mono<Integer> getNextVersion(String promptId) {
        if (promptId == null) {
            return Mono.just(1);
        }
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion)
                    .last("LIMIT 1");
            PromptVersion latest = promptVersionMapper.selectOne(wrapper);
            return latest != null ? latest.getVersion() + 1 : 1;
        });
    }

    @Override
    public Mono<List<String>> extractVariables(String content) {
        return Mono.just(TextSplitUtil.extractVariables(content));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> deletePromptVersions(String promptId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId);
            int deleted = promptVersionMapper.delete(wrapper);
            log.info("Deleted {} prompt versions for promptId={}", deleted, promptId);
            return deleted > 0;
        });
    }
}
