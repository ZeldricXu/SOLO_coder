package com.taskflow.core.task.internal.handler;

import com.taskflow.common.utils.AssertUtils;
import com.taskflow.common.utils.JsonUtils;
import com.taskflow.core.task.api.TaskHandler;
import com.taskflow.core.task.domain.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * HTTP调用任务处理器
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
public class HttpCallTaskHandler implements TaskHandler {

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public String getType() {
        return "http_call";
    }

    @Override
    public boolean validate(Map<String, Object> parameters) {
        AssertUtils.notBlank((String) parameters.get("url"), "URL不能为空");
        return true;
    }

    @Override
    public Object handle(Map<String, Object> parameters, ExecutionContext context) throws Exception {
        String url = (String) parameters.get("url");
        String method = (String) parameters.getOrDefault("method", "GET");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) parameters.get("body");

        log.info("Executing HTTP call: {} {}", method, url);

        String response = webClient.method(org.springframework.http.HttpMethod.valueOf(method.toUpperCase()))
                .uri(url)
                .bodyValue(body != null ? JsonUtils.toJson(body) : "")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return Map.of("response", response);
    }
}
