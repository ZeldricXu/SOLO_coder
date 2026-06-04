package com.cicd.runner.executor;

import com.cicd.grpc.PipelineJob;
import com.cicd.grpc.PipelineStep;
import com.cicd.grpc.WebhookConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class WebhookStepExecutor implements StepExecutor {

    private final OkHttpClient httpClient;

    public WebhookStepExecutor() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public boolean execute(PipelineStep step, PipelineJob job, Map<String, String> env, Consumer<String> logConsumer) throws Exception {
        WebhookConfig webhookConfig = step.getWebhook();
        if (webhookConfig == null) {
            logConsumer.accept("ERROR: No webhook config provided");
            return false;
        }

        String url = webhookConfig.getUrl();
        String method = webhookConfig.getMethod() != null && !webhookConfig.getMethod().isEmpty() ? webhookConfig.getMethod().toUpperCase() : "POST";
        Map<String, String> headers = webhookConfig.getHeadersMap();
        String body = webhookConfig.getBody();

        logConsumer.accept("Calling webhook: " + method + " " + url);
        if (!headers.isEmpty()) {
            logConsumer.accept("Headers: " + headers);
        }

        Request.Builder requestBuilder = new Request.Builder().url(url);

        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestBuilder.header(header.getKey(), header.getValue());
        }

        RequestBody requestBody = null;
        if (body != null && !body.isEmpty()) {
            String contentType = headers.getOrDefault("Content-Type", "application/json");
            requestBody = RequestBody.create(body, MediaType.parse(contentType));
            logConsumer.accept("Body: " + body);
        }

        switch (method) {
            case "GET" -> requestBuilder.get();
            case "POST" -> requestBuilder.post(requestBody != null ? requestBody : RequestBody.create(new byte[0]));
            case "PUT" -> requestBuilder.put(requestBody != null ? requestBody : RequestBody.create(new byte[0]));
            case "PATCH" -> requestBuilder.patch(requestBody != null ? requestBody : RequestBody.create(new byte[0]));
            case "DELETE" -> {
                if (requestBody != null) {
                    requestBuilder.delete(requestBody);
                } else {
                    requestBuilder.delete();
                }
            }
            case "HEAD" -> requestBuilder.head();
            default -> {
                logConsumer.accept("ERROR: Unsupported HTTP method: " + method);
                return false;
            }
        }

        Request request = requestBuilder.build();
        int timeout = step.getTimeoutSeconds() > 0 ? step.getTimeoutSeconds() : 60;

        try (Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";

            logConsumer.accept("Response status: " + statusCode);
            if (!responseBody.isEmpty()) {
                logConsumer.accept("Response body: " + responseBody);
            }

            if (statusCode >= 200 && statusCode < 300) {
                logConsumer.accept("Webhook call completed successfully");
                return true;
            } else {
                logConsumer.accept("ERROR: Webhook call failed with status " + statusCode);
                return step.getContinueOnError();
            }
        } catch (Exception e) {
            logConsumer.accept("ERROR: Webhook call failed: " + e.getMessage());
            log.error("Webhook call failed", e);
            return step.getContinueOnError();
        }
    }
}
