package com.logmanager.infrastructure.config;

import com.logmanager.service.pipeline.LogEnricherChain;
import com.logmanager.service.pipeline.enricher.IdEnricher;
import com.logmanager.service.pipeline.enricher.TimestampEnricher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Configuration
@RequiredArgsConstructor
public class PipelineConfig {

    private final LogEnricherChain enricherChain;

    @PostConstruct
    public void initPipeline() {
        enricherChain.addEnricher("timestamp", new TimestampEnricher(), 0);
        enricherChain.addEnricher("id", new IdEnricher(), 1);
    }
}
