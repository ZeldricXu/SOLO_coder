package com.loganalytics.pipeline.topology;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.serde.LogEventSerde;
import com.loganalytics.pipeline.cmdb.CmdbService;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.pipeline.enrich.LogEnricher;
import com.loganalytics.pipeline.filter.LogFilter;
import com.loganalytics.pipeline.geo.GeoIpService;
import com.loganalytics.pipeline.parse.LogParser;
import com.loganalytics.pipeline.processor.ProcessorChain;
import com.loganalytics.pipeline.processor.FilterProcessor;
import com.loganalytics.pipeline.processor.ParseProcessor;
import com.loganalytics.pipeline.processor.RouteProcessor;
import com.loganalytics.pipeline.processor.EnrichProcessor;
import com.loganalytics.pipeline.route.LogRouter;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineTopology {
    private static final Logger log = LoggerFactory.getLogger(PipelineTopology.class);

    private final PipelineConfig config;
    private final ProcessorChain chain;
    private final LogParser parser;
    private final LogFilter filter;
    private final LogEnricher enricher;
    private final LogRouter router;
    private final CmdbService cmdbService;
    private final GeoIpService geoIpService;

    public PipelineTopology(PipelineConfig config, ProcessorChain chain) {
        this.config = config;
        this.chain = chain;
        this.cmdbService = new CmdbService(config);
        this.geoIpService = new GeoIpService(config);
        this.parser = new LogParser(config);
        this.filter = new LogFilter(config);
        this.enricher = new LogEnricher(config, cmdbService, geoIpService);
        this.router = new LogRouter(config);
    }

    public PipelineTopology(PipelineConfig config) {
        this(config, null);
    }

    public StreamsBuilder build() {
        StreamsBuilder builder = new StreamsBuilder();
        LogEventSerde logEventSerde = new LogEventSerde();

        KStream<String, LogEvent> rawStream = builder.stream(
                config.getInputTopic(),
                Consumed.with(Serdes.String(), logEventSerde)
                        .withName("raw-logs-consumer")
        );

        if (chain != null) {
            rawStream
                    .processValues(() -> new ChainKafkaProcessor(chain),
                            Named.as("chain-processor"))
                    .to(
                            config.getEnrichedTopic(),
                            Produced.with(Serdes.String(), logEventSerde)
                                    .withName("enriched-logs-producer")
                    );
        } else {
            KStream<String, LogEvent> parsedStream = rawStream
                    .processValues(() -> new ParseKafkaProcessor(parser),
                            Named.as("parse-processor"));

            KStream<String, LogEvent> filteredStream = parsedStream
                    .filter((key, event) -> filter.accept(event),
                            Named.as("filter-processor"));

            KStream<String, LogEvent> enrichedStream = filteredStream
                    .processValues(() -> new EnrichKafkaProcessor(enricher),
                            Named.as("enrich-processor"));

            enrichedStream
                    .processValues(() -> new RouteKafkaProcessor(router, config),
                            Named.as("route-processor"));

            enrichedStream.to(
                    config.getEnrichedTopic(),
                    Produced.with(Serdes.String(), logEventSerde)
                            .withName("enriched-logs-producer")
            );
        }

        return builder;
    }

    static class ChainKafkaProcessor implements Processor<String, LogEvent, String, LogEvent> {
        private final ProcessorChain chain;
        private ProcessorContext<String, LogEvent> context;

        ChainKafkaProcessor(ProcessorChain chain) {
            this.chain = chain;
        }

        @Override
        public void init(ProcessorContext<String, LogEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, LogEvent> record) {
            LogEvent result = chain.process(record.value());
            if (result != null) {
                context.forward(record.withValue(result));
            }
        }

        @Override
        public void close() {
        }
    }

    static class ParseKafkaProcessor implements Processor<String, LogEvent, String, LogEvent> {
        private final LogParser parser;
        private ProcessorContext<String, LogEvent> context;

        ParseKafkaProcessor(LogParser parser) {
            this.parser = parser;
        }

        @Override
        public void init(ProcessorContext<String, LogEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, LogEvent> record) {
            LogEvent parsed = parser.parse(record.value());
            context.forward(record.withValue(parsed));
        }

        @Override
        public void close() {
        }
    }

    static class EnrichKafkaProcessor implements Processor<String, LogEvent, String, LogEvent> {
        private final LogEnricher enricher;
        private ProcessorContext<String, LogEvent> context;

        EnrichKafkaProcessor(LogEnricher enricher) {
            this.enricher = enricher;
        }

        @Override
        public void init(ProcessorContext<String, LogEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, LogEvent> record) {
            LogEvent enriched = enricher.enrich(record.value());
            context.forward(record.withValue(enriched));
        }

        @Override
        public void close() {
        }
    }

    static class RouteKafkaProcessor implements Processor<String, LogEvent, String, LogEvent> {
        private final LogRouter router;
        private final PipelineConfig config;
        private ProcessorContext<String, LogEvent> context;
        private final LogEventSerde logEventSerde;

        RouteKafkaProcessor(LogRouter router, PipelineConfig config) {
            this.router = router;
            this.config = config;
            this.logEventSerde = new LogEventSerde();
        }

        @Override
        public void init(ProcessorContext<String, LogEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, LogEvent> record) {
            LogEvent event = record.value();
            java.util.List<LogRouter.RouteTarget> targets = router.route(event);

            for (LogRouter.RouteTarget target : targets) {
                String topic = getTopicForTarget(target);
                if (topic != null) {
                    byte[] value = logEventSerde.serializer().serialize(topic, event);
                    Record<String, byte[]> outRecord = new Record<>(
                            record.key(),
                            value,
                            record.timestamp()
                    );
                    try {
                        var child = context.getChild(topic);
                        if (child != null) {
                            child.process((Record) outRecord);
                        }
                    } catch (Exception e) {
                        log.debug("Direct routing failed, using default forwarding");
                    }
                }
            }

            context.forward(record);
        }

        private String getTopicForTarget(LogRouter.RouteTarget target) {
            return switch (target) {
                case ERROR_LOGS -> config.getErrorTopic();
                case ANOMALY_DETECTION -> config.getAnomalyTopic();
                case ARCHIVE -> config.getArchiveTopic();
                case ANALYTICS -> config.getParsedTopic();
                case DROP -> null;
            };
        }

        @Override
        public void close() {
        }
    }

    public LogFilter getFilter() {
        if (chain != null) {
            for (var p : chain.getProcessors()) {
                if (p instanceof FilterProcessor fp) {
                    return fp.getFilter();
                }
            }
        }
        return filter;
    }

    public LogRouter getRouter() {
        if (chain != null) {
            for (var p : chain.getProcessors()) {
                if (p instanceof RouteProcessor rp) {
                    return rp.getRouter();
                }
            }
        }
        return router;
    }

    public LogParser getParser() {
        if (chain != null) {
            for (var p : chain.getProcessors()) {
                if (p instanceof ParseProcessor pp) {
                    return pp.getParser();
                }
            }
        }
        return parser;
    }

    public LogEnricher getEnricher() {
        if (chain != null) {
            for (var p : chain.getProcessors()) {
                if (p instanceof EnrichProcessor ep) {
                    return ep.getEnricher();
                }
            }
        }
        return enricher;
    }
}
