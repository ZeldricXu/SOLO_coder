package com.enterprise.gateway.observability.config;

import brave.Tracing;
import brave.handler.SpanHandler;
import brave.reporter.LoggingReporter;
import brave.sampler.Sampler;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient;
import org.springframework.data.elasticsearch.client.reactive.ReactiveRestClients;
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Configuration
public class ObservabilityConfig {

    @Value("${spring.elasticsearch.host:localhost}")
    private String elasticsearchHost;

    @Value("${spring.elasticsearch.port:9200}")
    private int elasticsearchPort;

    @Value("${gateway.logging.index-prefix:gateway-access-logs}")
    private String indexPrefix;

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            try {
                registry.config().commonTags(
                        "application", "gateway-service",
                        "host", InetAddress.getLocalHost().getHostName()
                );
            } catch (UnknownHostException e) {
                registry.config().commonTags(
                        "application", "gateway-service",
                        "host", "unknown"
                );
            }
        };
    }

    @Bean
    public Tracing braveTracing() {
        return Tracing.newBuilder()
                .localServiceName("gateway-service")
                .sampler(Sampler.ALWAYS_SAMPLE)
                .addSpanHandler(SpanHandler.create(LoggingReporter.create()))
                .supportsJoin(true)
                .traceId128Bit(true)
                .build();
    }

    @Bean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public MeterFilter meterFilter() {
        return MeterFilter.deny(id -> {
            String uri = id.getTag("uri");
            return uri != null && (uri.startsWith("/actuator") || uri.startsWith("/swagger"));
        });
    }

    @Bean
    public ReactiveElasticsearchClient reactiveElasticsearchClient() {
        ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectedTo(elasticsearchHost + ":" + elasticsearchPort)
                .build();
        return ReactiveRestClients.create(clientConfiguration);
    }

    @Bean
    public ReactiveElasticsearchTemplate reactiveElasticsearchTemplate(ReactiveElasticsearchClient client) {
        ElasticsearchConverter converter = new MappingElasticsearchConverter(new SimpleElasticsearchMappingContext());
        return new ReactiveElasticsearchTemplate(client, converter);
    }

    public String getElasticsearchHost() {
        return elasticsearchHost;
    }

    public int getElasticsearchPort() {
        return elasticsearchPort;
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }
}
