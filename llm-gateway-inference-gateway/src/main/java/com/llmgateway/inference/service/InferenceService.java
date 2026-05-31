package com.llmgateway.inference.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.inference.dto.InferenceDTO;
import com.llmgateway.inference.dto.InferenceResponse;
import com.llmgateway.inference.entity.InferenceRequest;
import com.llmgateway.inference.entity.ProviderConfig;
import com.llmgateway.inference.mapper.InferenceRequestMapper;
import com.llmgateway.inference.mapper.ProviderConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceService {

    private final InferenceRequestMapper requestMapper;
    private final ProviderConfigMapper providerConfigMapper;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    @Transactional(rollbackFor = Exception.class)
    public InferenceResponse inference(InferenceDTO dto) {
        long startTime = System.currentTimeMillis();

        InferenceRequest request = new InferenceRequest();
        request.setRequestId(IdGenerator.generateId("req"));
        request.setModelId(dto.getModelId());
        request.setPrompt(dto.getPrompt());
        request.setMaxTokens(dto.getMaxTokens());
        request.setTemperature(dto.getTemperature());
        request.setTopP(dto.getTopP());
        request.setStatus(CommonConstants.STATUS_RUNNING);
        requestMapper.insert(request);

        try {
            ProviderConfig provider = selectProvider(dto.getProvider());
            request.setProvider(provider.getProviderName());

            String response = callProvider(provider, dto);

            InferenceResponse ir = new InferenceResponse();
            ir.setRequestId(request.getRequestId());
            ir.setModelId(dto.getModelId());
            ir.setProvider(provider.getProviderName());
            ir.setResponseText(response);
            ir.setPromptTokens(dto.getPrompt().length() / 4);
            ir.setCompletionTokens(response.length() / 4);
            ir.setTotalTokens(ir.getPromptTokens() + ir.getCompletionTokens());
            ir.setLatencyMs(System.currentTimeMillis() - startTime);
            ir.setFallbackUsed(false);

            request.setStatus(CommonConstants.STATUS_SUCCESS);
            request.setResponseText(response);
            request.setPromptTokens(ir.getPromptTokens());
            request.setCompletionTokens(ir.getCompletionTokens());
            request.setTotalTokens(ir.getTotalTokens());
            request.setLatencyMs(ir.getLatencyMs());
            request.setCompletedAt(LocalDateTime.now());
            requestMapper.updateById(request);

            return ir;
        } catch (Exception e) {
            log.error("推理请求失败, 尝试降级: requestId={}", request.getRequestId(), e);
            try {
                InferenceResponse fallbackResponse = executeFallback(dto, request, startTime, e.getMessage());
                return fallbackResponse;
            } catch (Exception fe) {
                request.setStatus(CommonConstants.STATUS_FAILED);
                request.setErrorCode("INFERENCE_FAILED");
                request.setErrorMessage(fe.getMessage());
                request.setCompletedAt(LocalDateTime.now());
                requestMapper.updateById(request);
                throw new BusinessException("推理服务不可用");
            }
        }
    }

    private ProviderConfig selectProvider(String preferredProvider) {
        LambdaQueryWrapper<ProviderConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProviderConfig::getEnabled, true)
                .eq(ProviderConfig::getDeleted, 0)
                .orderByDesc(ProviderConfig::getPriority);

        if (preferredProvider != null) {
            wrapper.eq(ProviderConfig::getProviderName, preferredProvider);
        }

        List<ProviderConfig> providers = providerConfigMapper.selectList(wrapper);
        if (providers.isEmpty()) {
            throw new BusinessException("没有可用的推理提供商");
        }

        int index = roundRobinCounter.getAndIncrement() % providers.size();
        return providers.get(index);
    }

    private String callProvider(ProviderConfig provider, InferenceDTO dto) {
        log.debug("调用提供商: {}, model: {}", provider.getProviderName(), dto.getModelId());
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "This is a simulated response from " + provider.getProviderName() +
                " for prompt: " + dto.getPrompt().substring(0, Math.min(50, dto.getPrompt().length())) + "...";
    }

    private InferenceResponse executeFallback(InferenceDTO dto, InferenceRequest request, long startTime, String reason) {
        LambdaQueryWrapper<ProviderConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProviderConfig::getEnabled, true)
                .eq(ProviderConfig::getFallbackEnabled, true)
                .ne(ProviderConfig::getProviderName, request.getProvider())
                .eq(ProviderConfig::getDeleted, 0)
                .orderByDesc(ProviderConfig::getPriority)
                .last("LIMIT 1");

        ProviderConfig fallbackProvider = providerConfigMapper.selectOne(wrapper);
        if (fallbackProvider == null) {
            throw new BusinessException("没有可用的降级提供商");
        }

        String response = callProvider(fallbackProvider, dto);

        InferenceResponse ir = new InferenceResponse();
        ir.setRequestId(request.getRequestId());
        ir.setModelId(dto.getModelId());
        ir.setProvider(fallbackProvider.getProviderName());
        ir.setResponseText(response);
        ir.setPromptTokens(dto.getPrompt().length() / 4);
        ir.setCompletionTokens(response.length() / 4);
        ir.setTotalTokens(ir.getPromptTokens() + ir.getCompletionTokens());
        ir.setLatencyMs(System.currentTimeMillis() - startTime);
        ir.setFallbackUsed(true);
        ir.setFallbackReason(reason);

        request.setStatus(CommonConstants.STATUS_SUCCESS);
        request.setProvider(fallbackProvider.getProviderName());
        request.setResponseText(response);
        request.setPromptTokens(ir.getPromptTokens());
        request.setCompletionTokens(ir.getCompletionTokens());
        request.setTotalTokens(ir.getTotalTokens());
        request.setLatencyMs(ir.getLatencyMs());
        request.setFallbackUsed(true);
        request.setFallbackReason(reason);
        request.setCompletedAt(LocalDateTime.now());
        requestMapper.updateById(request);

        log.info("降级成功: requestId={}, fallbackProvider={}", request.getRequestId(), fallbackProvider.getProviderName());
        return ir;
    }

    public InferenceRequest getRequest(String requestId) {
        InferenceRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException(404, "请求不存在");
        }
        return request;
    }
}
