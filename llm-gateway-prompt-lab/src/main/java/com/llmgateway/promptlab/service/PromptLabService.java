package com.llmgateway.promptlab.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.promptlab.entity.AbExperiment;
import com.llmgateway.promptlab.entity.PromptTemplate;
import com.llmgateway.promptlab.mapper.AbExperimentMapper;
import com.llmgateway.promptlab.mapper.PromptTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptLabService {

    private final PromptTemplateMapper promptMapper;
    private final AbExperimentMapper experimentMapper;

    @Transactional(rollbackFor = Exception.class)
    public PromptTemplate createPrompt(PromptTemplate prompt) {
        prompt.setPromptId(IdGenerator.generatePromptId());
        prompt.setVersion(1);
        prompt.setStatus(prompt.getStatus() != null ? prompt.getStatus() : "draft");
        promptMapper.insert(prompt);
        log.info("Prompt模板创建成功: promptId={}", prompt.getPromptId());
        return prompt;
    }

    public PromptTemplate getPrompt(String promptId) {
        PromptTemplate prompt = promptMapper.selectById(promptId);
        if (prompt == null) {
            throw new BusinessException(404, "Prompt模板不存在");
        }
        return prompt;
    }

    public PageResult<PromptTemplate> listPrompts(String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(PromptTemplate::getStatus, status);
        }
        wrapper.eq(PromptTemplate::getDeleted, 0);
        wrapper.orderByDesc(PromptTemplate::getCreatedAt);

        IPage<PromptTemplate> page = promptMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public PromptTemplate updatePrompt(String promptId, PromptTemplate dto) {
        PromptTemplate prompt = getPrompt(promptId);
        prompt.setPromptName(dto.getPromptName());
        prompt.setDescription(dto.getDescription());
        prompt.setTemplate(dto.getTemplate());
        prompt.setVariables(dto.getVariables());
        prompt.setModelConfig(dto.getModelConfig());
        prompt.setTags(dto.getTags());
        prompt.setVersion(prompt.getVersion() + 1);
        prompt.setUpdatedAt(LocalDateTime.now());
        promptMapper.updateById(prompt);
        log.info("Prompt模板更新成功: promptId={}, version={}", promptId, prompt.getVersion());
        return prompt;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePrompt(String promptId) {
        PromptTemplate prompt = getPrompt(promptId);
        prompt.setDeleted(1);
        prompt.setUpdatedAt(LocalDateTime.now());
        promptMapper.updateById(prompt);
    }

    public String renderPrompt(String promptId, Map<String, Object> variables) {
        PromptTemplate prompt = getPrompt(promptId);
        String template = prompt.getTemplate();
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
        }
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    public AbExperiment createExperiment(AbExperiment experiment) {
        experiment.setExperimentId(IdGenerator.generateExperimentId());
        experiment.setStatus(experiment.getStatus() != null ? experiment.getStatus() : "draft");
        experiment.setExperimentType(experiment.getExperimentType() != null ? experiment.getExperimentType() : "ab");
        experiment.setStartTime(experiment.getStartTime() != null ? experiment.getStartTime() : LocalDateTime.now());
        experimentMapper.insert(experiment);
        log.info("AB实验创建成功: experimentId={}", experiment.getExperimentId());
        return experiment;
    }

    public AbExperiment getExperiment(String experimentId) {
        AbExperiment experiment = experimentMapper.selectById(experimentId);
        if (experiment == null) {
            throw new BusinessException(404, "实验不存在");
        }
        return experiment;
    }

    public PageResult<AbExperiment> listExperiments(String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<AbExperiment> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(AbExperiment::getStatus, status);
        }
        wrapper.eq(AbExperiment::getDeleted, 0);
        wrapper.orderByDesc(AbExperiment::getCreatedAt);

        IPage<AbExperiment> page = experimentMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public AbExperiment startExperiment(String experimentId) {
        AbExperiment experiment = getExperiment(experimentId);
        experiment.setStatus("running");
        experiment.setStartTime(LocalDateTime.now());
        experiment.setUpdatedAt(LocalDateTime.now());
        experimentMapper.updateById(experiment);
        log.info("AB实验启动: experimentId={}", experimentId);
        return experiment;
    }

    @Transactional(rollbackFor = Exception.class)
    public AbExperiment stopExperiment(String experimentId) {
        AbExperiment experiment = getExperiment(experimentId);
        experiment.setStatus("completed");
        experiment.setEndTime(LocalDateTime.now());
        experiment.setUpdatedAt(LocalDateTime.now());
        experimentMapper.updateById(experiment);
        log.info("AB实验停止: experimentId={}", experimentId);
        return experiment;
    }

    public String assignVariant(String experimentId, String userId) {
        AbExperiment experiment = getExperiment(experimentId);
        if (!"running".equals(experiment.getStatus())) {
            throw new BusinessException("实验未运行");
        }

        Map<String, Object> variants = experiment.getVariants();
        if (variants == null || variants.isEmpty()) {
            throw new BusinessException("实验没有配置变体");
        }

        int hash = Math.abs((userId + experimentId).hashCode());
        int percentage = hash % 100;

        if (percentage >= experiment.getTrafficPercentage()) {
            return "control";
        }

        Object[] variantKeys = variants.keySet().toArray();
        int variantIndex = hash % variantKeys.length;
        return (String) variantKeys[variantIndex];
    }
}
