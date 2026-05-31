package com.datastandard.modules.streaming.planner;

import com.datastandard.modules.streaming.ast.LogicalPlan;
import com.datastandard.modules.streaming.ast.PhysicalPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class PhysicalPlanTranslator {

    public PhysicalPlan translate(LogicalPlan logicalPlan, PhysicalPlan.ExecutionEngine engine) {
        log.info("翻译逻辑计划到物理计划, 引擎: {}", engine);

        PhysicalPlan root = null;
        PhysicalPlan current = null;

        if (engine == PhysicalPlan.ExecutionEngine.FLINK) {
            root = translateToFlink(logicalPlan);
        } else if (engine == PhysicalPlan.ExecutionEngine.SPARK_STRUCTURED_STREAMING) {
            root = translateToSpark(logicalPlan);
        } else if (engine == PhysicalPlan.ExecutionEngine.KAFKA_STREAMS) {
            root = translateToKafkaStreams(logicalPlan);
        }

        return root;
    }

    private PhysicalPlan translateToFlink(LogicalPlan logicalPlan) {
        log.debug("翻译到Flink物理计划");

        PhysicalPlan result = new PhysicalPlan();
        result.setEngine(PhysicalPlan.ExecutionEngine.FLINK);

        switch (logicalPlan.getType()) {
            case SCAN:
                result.setOperation("DataStreamSource");
                Map<String, Object> scanConfig = new HashMap<>();
                scanConfig.put("connector", logicalPlan.getProperties().get("connector"));
                scanConfig.put("tableName", logicalPlan.getProperties().get("tableName"));
                scanConfig.put("parallelism", logicalPlan.getProperties().getOrDefault("parallelism", 1));
                scanConfig.put("watermarkStrategy", "forBoundedOutOfOrderness");
                scanConfig.put("maxOutOfOrderness", "5s");
                result.setConfig(scanConfig);
                break;
            case FILTER:
                result.setOperation("FilterFunction");
                Map<String, Object> filterConfig = new HashMap<>();
                filterConfig.put("condition", logicalPlan.getProperties().get("condition"));
                filterConfig.put("operatorType", "filter");
                filterConfig.put("uid", generateUid(logicalPlan));
                filterConfig.put("name", "Filter-" + logicalPlan.getProperties().get("condition"));
                result.setConfig(filterConfig);
                break;
            case PROJECT:
                result.setOperation("MapFunction");
                Map<String, Object> projectConfig = new HashMap<>();
                projectConfig.put("columns", logicalPlan.getProperties().get("columns"));
                projectConfig.put("operatorType", "map");
                projectConfig.put("uid", generateUid(logicalPlan));
                projectConfig.put("name", "Projection");
                projectConfig.put("outputType", "Row");
                result.setConfig(projectConfig);
                break;
            case AGGREGATE:
                result.setOperation("KeyedProcessFunction");
                Map<String, Object> aggConfig = new HashMap<>();
                aggConfig.put("groupBy", logicalPlan.getProperties().get("groupBy"));
                aggConfig.put("aggregations", logicalPlan.getProperties().get("aggregations"));
                aggConfig.put("operatorType", "keyedProcess");
                aggConfig.put("stateTtl", "24h");
                aggConfig.put("timerService", "eventTime");
                result.setConfig(aggConfig);
                break;
            case JOIN:
                result.setOperation("CoProcessFunction");
                Map<String, Object> joinConfig = new HashMap<>();
                joinConfig.put("joinType", logicalPlan.getProperties().get("joinType"));
                joinConfig.put("joinKeys", logicalPlan.getProperties().get("joinKeys"));
                joinConfig.put("operatorType", "coProcess");
                joinConfig.put("windowType", "interval");
                joinConfig.put("leftLowerBound", "-5s");
                joinConfig.put("leftUpperBound", "5s");
                result.setConfig(joinConfig);
                break;
            case WINDOW_AGG:
                result.setOperation("WindowFunction");
                Map<String, Object> windowConfig = new HashMap<>();
                windowConfig.put("windowType", logicalPlan.getProperties().get("windowType"));
                windowConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                windowConfig.put("slideSize", logicalPlan.getProperties().get("slideSize"));
                windowConfig.put("gap", logicalPlan.getProperties().get("gap"));
                windowConfig.put("operatorType", "window");
                windowConfig.put("trigger", "EventTimeTrigger");
                windowConfig.put("allowedLateness", "1h");
                result.setConfig(windowConfig);
                break;
            case TUMBLE_WINDOW:
                result.setOperation("TumblingWindowFunction");
                Map<String, Object> tumbleConfig = new HashMap<>();
                tumbleConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                tumbleConfig.put("operatorType", "tumblingWindow");
                tumbleConfig.put("trigger", "EventTimeTrigger");
                tumbleConfig.put("aggregateFunctions", logicalPlan.getProperties().get("aggregations"));
                result.setConfig(tumbleConfig);
                break;
            case HOP_WINDOW:
                result.setOperation("SlidingWindowFunction");
                Map<String, Object> hopConfig = new HashMap<>();
                hopConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                hopConfig.put("slideSize", logicalPlan.getProperties().get("slideSize"));
                hopConfig.put("operatorType", "slidingWindow");
                hopConfig.put("trigger", "EventTimeTrigger");
                result.setConfig(hopConfig);
                break;
            case SESSION_WINDOW:
                result.setOperation("SessionWindowFunction");
                Map<String, Object> sessionConfig = new HashMap<>();
                sessionConfig.put("gap", logicalPlan.getProperties().get("gap"));
                sessionConfig.put("operatorType", "sessionWindow");
                sessionConfig.put("trigger", "EventTimeTrigger");
                sessionConfig.put("merging", true);
                result.setConfig(sessionConfig);
                break;
            case SORT:
                result.setOperation("KeyedSortFunction");
                Map<String, Object> sortConfig = new HashMap<>();
                sortConfig.put("sortBy", logicalPlan.getProperties().get("sortBy"));
                sortConfig.put("order", logicalPlan.getProperties().getOrDefault("order", "ASC"));
                sortConfig.put("operatorType", "sort");
                sortConfig.put("limit", logicalPlan.getProperties().get("limit"));
                result.setConfig(sortConfig);
                break;
            case LIMIT:
                result.setOperation("LimitFunction");
                Map<String, Object> limitConfig = new HashMap<>();
                limitConfig.put("limit", logicalPlan.getProperties().get("limit"));
                limitConfig.put("operatorType", "limit");
                limitConfig.put("global", logicalPlan.getProperties().getOrDefault("global", false));
                result.setConfig(limitConfig);
                break;
            case DISTINCT:
                result.setOperation("DistinctFunction");
                Map<String, Object> distinctConfig = new HashMap<>();
                distinctConfig.put("columns", logicalPlan.getProperties().get("columns"));
                distinctConfig.put("operatorType", "distinct");
                distinctConfig.put("stateRetention", "24h");
                result.setConfig(distinctConfig);
                break;
            default:
                result.setOperation("PassThroughFunction");
                Map<String, Object> defaultConfig = new HashMap<>();
                defaultConfig.put("operatorType", "passthrough");
                defaultConfig.put("originalType", logicalPlan.getType().name());
                result.setConfig(defaultConfig);
        }

        if (!logicalPlan.getChildren().isEmpty()) {
            PhysicalPlan childPlan = translateToFlink(logicalPlan.getChildren().get(0));
            result.setChild(childPlan);
        }

        return result;
    }

    private PhysicalPlan translateToSpark(LogicalPlan logicalPlan) {
        log.debug("翻译到Spark Structured Streaming物理计划");

        PhysicalPlan result = new PhysicalPlan();
        result.setEngine(PhysicalPlan.ExecutionEngine.SPARK_STRUCTURED_STREAMING);

        switch (logicalPlan.getType()) {
            case SCAN:
                result.setOperation("DataFrameReader");
                Map<String, Object> scanConfig = new HashMap<>();
                scanConfig.put("format", logicalPlan.getProperties().get("connector"));
                scanConfig.put("tableName", logicalPlan.getProperties().get("tableName"));
                scanConfig.put("options", logicalPlan.getProperties().get("options"));
                result.setConfig(scanConfig);
                break;
            case FILTER:
                result.setOperation("Filter");
                Map<String, Object> filterConfig = new HashMap<>();
                filterConfig.put("condition", logicalPlan.getProperties().get("condition"));
                filterConfig.put("expr", logicalPlan.getProperties().get("condition"));
                result.setConfig(filterConfig);
                break;
            case PROJECT:
                result.setOperation("Select");
                Map<String, Object> projectConfig = new HashMap<>();
                projectConfig.put("columns", logicalPlan.getProperties().get("columns"));
                projectConfig.put("exprs", logicalPlan.getProperties().get("columns"));
                result.setConfig(projectConfig);
                break;
            case AGGREGATE:
                result.setOperation("GroupedAggregate");
                Map<String, Object> aggConfig = new HashMap<>();
                aggConfig.put("groupBy", logicalPlan.getProperties().get("groupBy"));
                aggConfig.put("aggExprs", logicalPlan.getProperties().get("aggregations"));
                result.setConfig(aggConfig);
                break;
            case JOIN:
                result.setOperation("Join");
                Map<String, Object> joinConfig = new HashMap<>();
                joinConfig.put("joinType", logicalPlan.getProperties().get("joinType"));
                joinConfig.put("joinExpr", logicalPlan.getProperties().get("joinKeys"));
                joinConfig.put("watermark", logicalPlan.getProperties().get("watermark"));
                result.setConfig(joinConfig);
                break;
            case WINDOW_AGG:
                result.setOperation("WindowAggregate");
                Map<String, Object> windowConfig = new HashMap<>();
                windowConfig.put("windowType", logicalPlan.getProperties().get("windowType"));
                windowConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                windowConfig.put("slideSize", logicalPlan.getProperties().get("slideSize"));
                windowConfig.put("gap", logicalPlan.getProperties().get("gap"));
                windowConfig.put("groupBy", logicalPlan.getProperties().get("groupBy"));
                result.setConfig(windowConfig);
                break;
            case TUMBLE_WINDOW:
                result.setOperation("TumblingWindow");
                Map<String, Object> tumbleConfig = new HashMap<>();
                tumbleConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                tumbleConfig.put("eventTime", logicalPlan.getProperties().get("eventTimeColumn"));
                result.setConfig(tumbleConfig);
                break;
            case HOP_WINDOW:
                result.setOperation("SlidingWindow");
                Map<String, Object> hopConfig = new HashMap<>();
                hopConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                hopConfig.put("slideSize", logicalPlan.getProperties().get("slideSize"));
                hopConfig.put("eventTime", logicalPlan.getProperties().get("eventTimeColumn"));
                result.setConfig(hopConfig);
                break;
            case SESSION_WINDOW:
                result.setOperation("SessionWindow");
                Map<String, Object> sessionConfig = new HashMap<>();
                sessionConfig.put("gap", logicalPlan.getProperties().get("gap"));
                sessionConfig.put("eventTime", logicalPlan.getProperties().get("eventTimeColumn"));
                result.setConfig(sessionConfig);
                break;
            default:
                result.setOperation("PassThrough");
                Map<String, Object> defaultConfig = new HashMap<>();
                defaultConfig.put("originalType", logicalPlan.getType().name());
                result.setConfig(defaultConfig);
        }

        if (!logicalPlan.getChildren().isEmpty()) {
            PhysicalPlan childPlan = translateToSpark(logicalPlan.getChildren().get(0));
            result.setChild(childPlan);
        }

        return result;
    }

    private PhysicalPlan translateToKafkaStreams(LogicalPlan logicalPlan) {
        log.debug("翻译到Kafka Streams物理计划");

        PhysicalPlan result = new PhysicalPlan();
        result.setEngine(PhysicalPlan.ExecutionEngine.KAFKA_STREAMS);

        switch (logicalPlan.getType()) {
            case SCAN:
                result.setOperation("KStreamSource");
                Map<String, Object> scanConfig = new HashMap<>();
                scanConfig.put("topic", logicalPlan.getProperties().get("tableName"));
                scanConfig.put("keySerde", logicalPlan.getProperties().getOrDefault("keySerde", "String"));
                scanConfig.put("valueSerde", logicalPlan.getProperties().getOrDefault("valueSerde", "Json"));
                scanConfig.put("timestampExtractor", "WallclockTimestampExtractor");
                result.setConfig(scanConfig);
                break;
            case FILTER:
                result.setOperation("Predicate");
                Map<String, Object> filterConfig = new HashMap<>();
                filterConfig.put("condition", logicalPlan.getProperties().get("condition"));
                filterConfig.put("operation", "filter");
                result.setConfig(filterConfig);
                break;
            case PROJECT:
                result.setOperation("KeyValueMapper");
                Map<String, Object> projectConfig = new HashMap<>();
                projectConfig.put("columns", logicalPlan.getProperties().get("columns"));
                projectConfig.put("operation", "map");
                result.setConfig(projectConfig);
                break;
            case AGGREGATE:
                result.setOperation("KGroupedStreamAggregate");
                Map<String, Object> aggConfig = new HashMap<>();
                aggConfig.put("groupBy", logicalPlan.getProperties().get("groupBy"));
                aggConfig.put("aggregator", logicalPlan.getProperties().get("aggregations"));
                aggConfig.put("storeName", generateStoreName(logicalPlan));
                aggConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                result.setConfig(aggConfig);
                break;
            case JOIN:
                result.setOperation("KStreamJoin");
                Map<String, Object> joinConfig = new HashMap<>();
                joinConfig.put("joinType", logicalPlan.getProperties().get("joinType"));
                joinConfig.put("joinWindows", logicalPlan.getProperties().get("windowSize"));
                joinConfig.put("valueJoiner", "customJoiner");
                result.setConfig(joinConfig);
                break;
            case WINDOW_AGG:
                result.setOperation("WindowedAggregate");
                Map<String, Object> windowConfig = new HashMap<>();
                windowConfig.put("windowType", logicalPlan.getProperties().get("windowType"));
                windowConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                windowConfig.put("slideSize", logicalPlan.getProperties().get("slideSize"));
                windowConfig.put("gap", logicalPlan.getProperties().get("gap"));
                windowConfig.put("aggregator", logicalPlan.getProperties().get("aggregations"));
                result.setConfig(windowConfig);
                break;
            case TUMBLE_WINDOW:
                result.setOperation("TumblingWindowedAggregate");
                Map<String, Object> tumbleConfig = new HashMap<>();
                tumbleConfig.put("windowSize", logicalPlan.getProperties().get("windowSize"));
                tumbleConfig.put("aggregator", logicalPlan.getProperties().get("aggregations"));
                tumbleConfig.put("storeName", generateStoreName(logicalPlan));
                result.setConfig(tumbleConfig);
                break;
            default:
                result.setOperation("PassThrough");
                Map<String, Object> defaultConfig = new HashMap<>();
                defaultConfig.put("originalType", logicalPlan.getType().name());
                result.setConfig(defaultConfig);
        }

        if (!logicalPlan.getChildren().isEmpty()) {
            PhysicalPlan childPlan = translateToKafkaStreams(logicalPlan.getChildren().get(0));
            result.setChild(childPlan);
        }

        return result;
    }

    private String generateUid(LogicalPlan plan) {
        return "uid-" + plan.getType().name() + "-" + Math.abs(plan.hashCode());
    }

    private String generateStoreName(LogicalPlan plan) {
        return "store-" + plan.getType().name() + "-" + Math.abs(plan.hashCode());
    }

    public String generateCode(PhysicalPlan plan, PhysicalPlan.ExecutionEngine engine) {
        log.info("生成执行代码, 引擎: {}", engine);

        StringBuilder code = new StringBuilder();

        if (engine == PhysicalPlan.ExecutionEngine.FLINK) {
            code.append("// Flink Job Code\n");
            code.append("StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();\n");
            code.append("DataStream<Row> stream = env.addSource(...);\n");

            PhysicalPlan current = plan;
            while (current != null) {
                code.append("// ").append(current.getOperation()).append("\n");
                code.append("stream = stream.").append(current.getOperation().toLowerCase()).append("(...);\n");
                current = current.getChild();
            }

            code.append("stream.execute();\n");
        } else if (engine == PhysicalPlan.ExecutionEngine.SPARK_STRUCTURED_STREAMING) {
            code.append("// Spark Structured Streaming Code\n");
            code.append("SparkSession spark = SparkSession.builder().getOrCreate();\n");
            code.append("Dataset<Row> df = spark.readStream().format(...).load();\n");

            PhysicalPlan current = plan;
            while (current != null) {
                code.append("// ").append(current.getOperation()).append("\n");
                code.append("df = df.").append(current.getOperation().toLowerCase()).append("(...);\n");
                current = current.getChild();
            }

            code.append("df.writeStream().start();\n");
        } else if (engine == PhysicalPlan.ExecutionEngine.KAFKA_STREAMS) {
            code.append("// Kafka Streams Code\n");
            code.append("StreamsBuilder builder = new StreamsBuilder();\n");
            code.append("KStream<String, String> stream = builder.stream(\"topic\");\n");

            PhysicalPlan current = plan;
            while (current != null) {
                code.append("// ").append(current.getOperation()).append("\n");
                String op = current.getOperation().toLowerCase().replace("kstream", "").replace("kgrouped", "");
                code.append("stream = stream.").append(op).append("(...);\n");
                current = current.getChild();
            }

            code.append("Topology topology = builder.build();\n");
            code.append("KafkaStreams streams = new KafkaStreams(topology, config);\n");
            code.append("streams.start();\n");
        }

        return code.toString();
    }
}
