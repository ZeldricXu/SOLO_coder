package com.taskplatform.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.persistence.entity.PromptVersion;
import com.taskplatform.persistence.mapper.PromptVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptVersionMapper promptVersionMapper;

    public PromptVersion createPrompt(PromptVersion prompt, String createdBy) {
        String promptId = prompt.getPromptId() != null ? prompt.getPromptId() : IdGenerator.generatePromptId();

        Integer latestVersion = promptVersionMapper.selectObjs(
                new LambdaQueryWrapper<PromptVersion>()
                        .eq(PromptVersion::getPromptId, promptId)
                        .select("MAX(version)")
        ).stream().findFirst().map(v -> (Integer) v).orElse(0);

        promptVersionMapper.update(
                null,
                new LambdaQueryWrapper<PromptVersion>()
                        .eq(PromptVersion::getPromptId, promptId)
                        .set(PromptVersion::getIsLatest, false)
        );

        prompt.setPromptId(promptId);
        prompt.setVersion(latestVersion + 1);
        prompt.setIsLatest(true);
        prompt.setCreatedBy(createdBy);

        promptVersionMapper.insert(prompt);
        log.info("Created prompt version: {} v{}", promptId, prompt.getVersion());
        return prompt;
    }

    public PromptVersion getPrompt(String promptId, Integer version) {
        LambdaQueryWrapper<PromptVersion> query = new LambdaQueryWrapper<PromptVersion>()
                .eq(PromptVersion::getPromptId, promptId);

        if (version != null) {
            query.eq(PromptVersion::getVersion, version);
        } else {
            query.eq(PromptVersion::getIsLatest, true);
        }

        PromptVersion prompt = promptVersionMapper.selectOne(query);
        if (prompt == null) {
            throw new BusinessException(404, "PROMPT_NOT_FOUND",
                    "Prompt not found: " + promptId + (version != null ? " v" + version : ""));
        }
        return prompt;
    }

    public List<PromptVersion> listPromptVersions(String promptId) {
        return promptVersionMapper.selectList(
                new LambdaQueryWrapper<PromptVersion>()
                        .eq(PromptVersion::getPromptId, promptId)
                        .orderByDesc(PromptVersion::getVersion)
        );
    }

    public PromptVersion updatePromptVariables(String promptId, Map<String, Object> variables, String updatedBy) {
        PromptVersion latest = getPrompt(promptId, null);

        PromptVersion newVersion = new PromptVersion();
        newVersion.setPromptId(promptId);
        newVersion.setName(latest.getName());
        newVersion.setContent(latest.getContent());
        newVersion.setTemplate(latest.getTemplate());
        newVersion.setVariables(JsonUtil.toJson(variables));
        newVersion.setModelId(latest.getModelId());
        newVersion.setTemperature(latest.getTemperature());
        newVersion.setMaxTokens(latest.getMaxTokens());
        newVersion.setDescription(latest.getDescription());
        newVersion.setTags(latest.getTags());

        return createPrompt(newVersion, updatedBy);
    }

    public String renderPrompt(String promptId, Map<String, Object> context) {
        PromptVersion prompt = getPrompt(promptId, null);
        String template = prompt.getTemplate() != null ? prompt.getTemplate() : prompt.getContent();

        if (template == null) {
            return prompt.getContent();
        }

        String rendered = template;
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                rendered = rendered.replace("{{" + entry.getKey() + "}}",
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        return rendered;
    }

    @Transactional
    public void deletePrompt(String promptId) {
        promptVersionMapper.delete(
                new LambdaQueryWrapper<PromptVersion>()
                        .eq(PromptVersion::getPromptId, promptId)
        );
    }
}
