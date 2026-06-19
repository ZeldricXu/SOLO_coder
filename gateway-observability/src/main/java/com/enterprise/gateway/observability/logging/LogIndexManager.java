package com.enterprise.gateway.observability.logging;

import jakarta.annotation.PostConstruct;
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.MappingBuilder;
import org.springframework.data.elasticsearch.core.index.Settings;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class LogIndexManager {

    private final ReactiveElasticsearchTemplate elasticsearchTemplate;

    public LogIndexManager(ReactiveElasticsearchTemplate elasticsearchTemplate) {
        this.elasticsearchTemplate = elasticsearchTemplate;
    }

    @PostConstruct
    public void init() {
        createDailyIndex(LocalDate.now()).subscribe();
    }

    public Mono<Void> createDailyIndex(LocalDate date) {
        String indexName = "gateway-access-logs-" + date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        return ensureIndexExists(indexName);
    }

    public Mono<Void> ensureIndexExists(String indexName) {
        return elasticsearchTemplate.indexOps().exists(indexName)
                .flatMap(exists -> exists ? Mono.empty() : createIndex(indexName))
                .then();
    }

    private Mono<Boolean> createIndex(String indexName) {
        Settings settings = Settings.builder()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 1)
                .put("index.refresh_interval", "5s")
                .build();

        String mapping = new MappingBuilder()
                .field("timestamp", "date")
                .field("method", "keyword")
                .field("path", "keyword")
                .field("status", "integer")
                .field("duration", "long")
                .field("clientIp", "keyword")
                .field("userId", "keyword")
                .field("routeId", "keyword")
                .field("requestSize", "long")
                .field("responseSize", "long")
                .field("userAgent", "text")
                .field("error", "text")
                .field("errorType", "keyword")
                .build();

        return elasticsearchTemplate.indexOps().create(indexName, settings, mapping)
                .flatMap(created -> {
                    if (created) {
                        AliasActions aliasActions = new AliasActions(
                                new AliasAction.Add(indexName, "gateway-access-logs", null, null, null, null)
                        );
                        return elasticsearchTemplate.indexOps().alias(aliasActions).thenReturn(true);
                    }
                    return Mono.just(false);
                });
    }
}
