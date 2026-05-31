package com.modelguard.service.prompt;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.dto.request.PromptVersionCreateRequest;
import com.modelguard.dto.response.PromptVersionResponse;
import com.modelguard.entity.PromptVersion;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface PromptVersionService {

    Mono<PromptVersionResponse> createPromptVersion(PromptVersionCreateRequest request);

    Mono<PromptVersionResponse> getPromptVersion(String promptId, Integer version);

    Mono<PromptVersion> getPromptVersionEntity(String promptId, Integer version);

    Mono<List<PromptVersionResponse>> listPromptVersions(String promptId);

    Mono<PageResult<PromptVersionResponse>> pagePromptVersions(String promptId, int pageNum, int pageSize);

    Mono<PromptVersionResponse> getLatestPromptVersion(String promptId);

    Mono<String> renderPrompt(String promptId, Integer version, Map<String, Object> variables);

    Mono<Integer> getNextVersion(String promptId);

    Mono<List<String>> extractVariables(String content);

    Mono<Boolean> deletePromptVersions(String promptId);
}
