package com.datastandard.modules.lineage.extractor;

import com.datastandard.modules.lineage.model.LineageEdge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SqlLineageExtractor implements LineageExtractor {

    private static final Pattern TABLE_PATTERN =
            Pattern.compile("(?:FROM|JOIN|UPDATE|INTO)\\s+(\\w+(?:\\.\\w+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTPUT_PATTERN =
            Pattern.compile("CREATE\\s+(?:TABLE|VIEW)\\s+(\\w+(?:\\.\\w+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_PATTERN =
            Pattern.compile("INSERT\\s+INTO\\s+(\\w+(?:\\.\\w+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERGE_PATTERN =
            Pattern.compile("MERGE\\s+INTO\\s+(\\w+(?:\\.\\w+)?)", Pattern.CASE_INSENSITIVE);

    @Override
    public List<LineageEdge> extract(Object input) {
        if (!(input instanceof String)) {
            return Collections.emptyList();
        }
        String sql = (String) input;
        log.info("从SQL提取血缘关系");

        Set<String> inputTables = extractInputTables(sql);
        Set<String> outputTables = extractOutputTables(sql);
        Set<String> columns = new HashSet<>();

        return buildEdges(inputTables, outputTables, columns, "SQL", LineageEdge.EdgeType.TRANSFORM);
    }

    private Set<String> extractInputTables(String sql) {
        Set<String> tables = new HashSet<>();
        Matcher tableMatcher = TABLE_PATTERN.matcher(sql);
        while (tableMatcher.find()) {
            tables.add(tableMatcher.group(1));
        }
        return tables;
    }

    private Set<String> extractOutputTables(String sql) {
        Set<String> tables = new HashSet<>();
        Matcher outputMatcher = OUTPUT_PATTERN.matcher(sql);
        while (outputMatcher.find()) {
            tables.add(outputMatcher.group(1));
        }
        Matcher insertMatcher = INSERT_PATTERN.matcher(sql);
        while (insertMatcher.find()) {
            tables.add(insertMatcher.group(1));
        }
        Matcher mergeMatcher = MERGE_PATTERN.matcher(sql);
        while (mergeMatcher.find()) {
            tables.add(mergeMatcher.group(1));
        }
        return tables;
    }

    private List<LineageEdge> buildEdges(Set<String> sources, Set<String> targets,
                                          Set<String> columns, String transformType,
                                          LineageEdge.EdgeType edgeType) {
        List<LineageEdge> edges = new ArrayList<>();
        for (String target : targets) {
            for (String source : sources) {
                LineageEdge edge = LineageEdge.builder()
                        .source(source)
                        .target(target)
                        .edgeType(edgeType.name())
                        .transformType(transformType)
                        .columns(new ArrayList<>(columns))
                        .timestamp(Instant.now())
                        .build();
                edges.add(edge);
            }
        }
        return edges;
    }

    @Override
    public String getExtractorType() {
        return "SQL";
    }
}
