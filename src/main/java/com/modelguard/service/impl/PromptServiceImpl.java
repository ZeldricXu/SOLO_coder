package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.dto.AbExperimentDTO;
import com.modelguard.dto.ExperimentResultRecordDTO;
import com.modelguard.dto.PromptVersionDTO;
import com.modelguard.entity.AbExperiment;
import com.modelguard.entity.AbExperimentResult;
import com.modelguard.entity.PromptVersion;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.AbExperimentMapper;
import com.modelguard.mapper.AbExperimentResultMapper;
import com.modelguard.mapper.PromptVersionMapper;
import com.modelguard.service.PromptService;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptServiceImpl implements PromptService {

    private final PromptVersionMapper promptVersionMapper;
    private final AbExperimentMapper abExperimentMapper;
    private final AbExperimentResultMapper abExperimentResultMapper;

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<PromptVersion> createPromptVersion(PromptVersionDTO dto) {
        return Mono.fromCallable(() -> {
            String promptId = dto.getPromptId() != null ? dto.getPromptId() : "prompt_" + IdUtil.simpleUUID();

            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion)
                    .last("LIMIT 1");
            PromptVersion latest = promptVersionMapper.selectOne(wrapper);

            int newVersion = latest != null ? latest.getVersion() + 1 : 1;

            PromptVersion version = new PromptVersion();
            version.setPromptId(promptId);
            version.setVersion(newVersion);
            version.setContent(dto.getContent());
            version.setVariables(dto.getVariables());
            version.setCreatedBy(dto.getCreatedBy());
            version.setDescription(dto.getDescription());

            promptVersionMapper.insert(version);
            log.info("Created prompt version: promptId={}, version={}", promptId, newVersion);
            return version;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Cacheable(value = "prompt_versions", key = "#promptId + '_' + #version", unless = "#result == null")
    public Mono<PromptVersion> getPromptVersion(String promptId, Integer version) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .eq(PromptVersion::getVersion, version);
            PromptVersion pv = promptVersionMapper.selectOne(wrapper);
            if (pv == null) {
                throw new ResourceNotFoundException("PromptVersion", promptId + " v" + version);
            }
            return pv;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<PromptVersion>> listPromptVersions(String promptId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion);
            return promptVersionMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PageResult<PromptVersion>> pagePromptVersions(String promptId, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<PromptVersion> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion);
            Page<PromptVersion> result = promptVersionMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<PromptVersion> getLatestPromptVersion(String promptId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PromptVersion::getPromptId, promptId)
                    .orderByDesc(PromptVersion::getVersion)
                    .last("LIMIT 1");
            PromptVersion pv = promptVersionMapper.selectOne(wrapper);
            if (pv == null) {
                throw new ResourceNotFoundException("PromptVersion", promptId);
            }
            return pv;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> renderPrompt(String promptId, Integer version, Map<String, Object> variables) {
        Mono<PromptVersion> pvMono = version != null ?
                getPromptVersion(promptId, version) :
                getLatestPromptVersion(promptId);

        return pvMono.map(pv -> {
            String content = pv.getContent();
            if (variables == null || variables.isEmpty()) {
                return content;
            }
            Matcher matcher = VARIABLE_PATTERN.matcher(content);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String key = matcher.group(1).trim();
                Object value = variables.get(key);
                String replacement = value != null ? Matcher.quoteReplacement(value.toString()) : "";
                matcher.appendReplacement(sb, replacement);
            }
            matcher.appendTail(sb);
            return sb.toString();
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperiment> createAbExperiment(AbExperimentDTO dto) {
        return Mono.fromCallable(() -> {
            validatePromptExists(dto.getControlGroupPromptId(), dto.getControlGroupPromptVersion());
            validatePromptExists(dto.getExperimentGroupPromptId(), dto.getExperimentGroupPromptVersion());

            AbExperiment experiment = new AbExperiment();
            experiment.setExperimentId("exp_" + IdUtil.simpleUUID());
            experiment.setName(dto.getName());
            experiment.setDescription(dto.getDescription());
            experiment.setControlGroupPromptId(dto.getControlGroupPromptId());
            experiment.setControlGroupPromptVersion(dto.getControlGroupPromptVersion());
            experiment.setExperimentGroupPromptId(dto.getExperimentGroupPromptId());
            experiment.setExperimentGroupPromptVersion(dto.getExperimentGroupPromptVersion());
            experiment.setTrafficSplit(dto.getTrafficSplit());
            experiment.setStatus("DRAFT");
            experiment.setCreatedBy(dto.getCreatedBy());

            abExperimentMapper.insert(experiment);
            log.info("Created AB experiment: experimentId={}", experiment.getExperimentId());
            return experiment;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void validatePromptExists(String promptId, Integer version) {
        LambdaQueryWrapper<PromptVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptVersion::getPromptId, promptId)
                .eq(PromptVersion::getVersion, version);
        if (promptVersionMapper.selectCount(wrapper) == 0) {
            throw new BusinessException("Prompt不存在: " + promptId + " v" + version);
        }
    }

    @Override
    public Mono<AbExperiment> getAbExperiment(String experimentId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AbExperiment> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperiment::getExperimentId, experimentId);
            AbExperiment exp = abExperimentMapper.selectOne(wrapper);
            if (exp == null) {
                throw new ResourceNotFoundException("AbExperiment", experimentId);
            }
            return exp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperiment> startAbExperiment(String experimentId) {
        return getAbExperiment(experimentId)
                .flatMap(exp -> {
                    if (!"DRAFT".equals(exp.getStatus()) && !"PAUSED".equals(exp.getStatus())) {
                        throw new BusinessException("实验状态不允许启动，当前状态: " + exp.getStatus());
                    }
                    exp.setStatus("RUNNING");
                    if (exp.getStartedAt() == null) {
                        exp.setStartedAt(LocalDateTime.now());
                    }
                    return Mono.fromCallable(() -> {
                        abExperimentMapper.updateById(exp);
                        return exp;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperiment> pauseAbExperiment(String experimentId) {
        return getAbExperiment(experimentId)
                .flatMap(exp -> {
                    if (!"RUNNING".equals(exp.getStatus())) {
                        throw new BusinessException("实验不在运行状态，无法暂停，当前状态: " + exp.getStatus());
                    }
                    exp.setStatus("PAUSED");
                    return Mono.fromCallable(() -> {
                        abExperimentMapper.updateById(exp);
                        return exp;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperiment> stopAbExperiment(String experimentId) {
        return getAbExperiment(experimentId)
                .flatMap(exp -> {
                    if ("COMPLETED".equals(exp.getStatus())) {
                        throw new BusinessException("实验已结束");
                    }
                    exp.setStatus("COMPLETED");
                    exp.setEndedAt(LocalDateTime.now());
                    return Mono.fromCallable(() -> {
                        abExperimentMapper.updateById(exp);
                        return exp;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    public Mono<PageResult<AbExperiment>> pageAbExperiments(String status, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            Page<AbExperiment> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<AbExperiment> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(AbExperiment::getStatus, status);
            }
            wrapper.orderByDesc(AbExperiment::getCreatedAt);
            Page<AbExperiment> result = abExperimentMapper.selectPage(page, wrapper);
            return PageResult.of(result.getRecords(), result.getTotal(), pageNum, pageSize);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<AbExperimentResult> recordExperimentResult(ExperimentResultRecordDTO dto) {
        return getAbExperiment(dto.getExperimentId())
                .flatMap(exp -> {
                    if (!"RUNNING".equals(exp.getStatus())) {
                        throw new BusinessException("实验未在运行中，无法记录结果");
                    }
                    if (!"CONTROL".equals(dto.getGroupType()) && !"EXPERIMENT".equals(dto.getGroupType())) {
                        throw new BusinessException("无效的分组类型: " + dto.getGroupType());
                    }
                    AbExperimentResult result = new AbExperimentResult();
                    result.setExperimentId(dto.getExperimentId());
                    result.setGroupType(dto.getGroupType());
                    result.setTotalRequests(dto.getTotalRequests());
                    result.setSuccessCount(dto.getSuccessCount());
                    result.setAvgLatencyMs(dto.getAvgLatencyMs());
                    result.setP99LatencyMs(dto.getP99LatencyMs());
                    result.setErrorRate(dto.getErrorRate());
                    result.setSatisfactionScore(dto.getSatisfactionScore());
                    result.setMetrics(dto.getMetrics());
                    result.setSnapshotTime(LocalDateTime.now());

                    return Mono.fromCallable(() -> {
                        abExperimentResultMapper.insert(result);
                        return result;
                    }).subscribeOn(Schedulers.boundedElastic());
                });
    }

    @Override
    public Mono<List<AbExperimentResult>> getExperimentResults(String experimentId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AbExperimentResult> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AbExperimentResult::getExperimentId, experimentId)
                    .orderByDesc(AbExperimentResult::getSnapshotTime);
            return abExperimentResultMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> compareExperimentResults(String experimentId) {
        return getExperimentResults(experimentId)
                .map(results -> {
                    Map<String, Object> comparison = new LinkedHashMap<>();
                    comparison.put("experimentId", experimentId);

                    AbExperimentResult latestControl = results.stream()
                            .filter(r -> "CONTROL".equals(r.getGroupType()))
                            .findFirst().orElse(null);
                    AbExperimentResult latestExperiment = results.stream()
                            .filter(r -> "EXPERIMENT".equals(r.getGroupType()))
                            .findFirst().orElse(null);

                    comparison.put("controlGroup", latestControl);
                    comparison.put("experimentGroup", latestExperiment);

                    if (latestControl != null && latestExperiment != null) {
                        Map<String, Object> diff = new LinkedHashMap<>();
                        diff.put("successRateDiff", latestExperiment.getSuccessCount() * 1.0 / latestExperiment.getTotalRequests() -
                                latestControl.getSuccessCount() * 1.0 / latestControl.getTotalRequests());
                        if (latestControl.getAvgLatencyMs() != null && latestExperiment.getAvgLatencyMs() != null) {
                            diff.put("avgLatencyDiffMs", latestExperiment.getAvgLatencyMs().subtract(latestControl.getAvgLatencyMs()));
                        }
                        if (latestControl.getErrorRate() != null && latestExperiment.getErrorRate() != null) {
                            diff.put("errorRateDiff", latestExperiment.getErrorRate().subtract(latestControl.getErrorRate()));
                        }
                        if (latestControl.getSatisfactionScore() != null && latestExperiment.getSatisfactionScore() != null) {
                            diff.put("satisfactionScoreDiff", latestExperiment.getSatisfactionScore().subtract(latestControl.getSatisfactionScore()));
                        }
                        comparison.put("differences", diff);
                    }

                    comparison.put("totalRecords", results.size());
                    return comparison;
                });
    }

    @Override
    public Mono<String> assignExperimentGroup(String experimentId, String userId) {
        return getAbExperiment(experimentId)
                .map(exp -> {
                    if (!"RUNNING".equals(exp.getStatus())) {
                        return "CONTROL";
                    }
                    int hash = Math.abs(HashUtil.fnvHash(userId + experimentId));
                    double normalized = (hash % 10000) / 10000.0;
                    if (normalized < exp.getTrafficSplit().doubleValue()) {
                        return "EXPERIMENT";
                    } else {
                        return "CONTROL";
                    }
                });
    }
}
