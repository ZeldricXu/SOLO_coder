package com.tsdbproxy.lineage.service;

import com.tsdbproxy.lineage.dto.LineageGraphResult;
import com.tsdbproxy.lineage.dto.LineageParseRequest;
import com.tsdbproxy.lineage.parser.SqlLineageParser;
import com.tsdbproxy.common.entity.LineageGraph;
import com.tsdbproxy.common.mapper.LineageGraphMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineageService {

    private final SqlLineageParser sqlLineageParser;
    private final LineageGraphMapper lineageGraphMapper;

    public Mono<LineageGraphResult> parseLineage(LineageParseRequest request) {
        return Mono.fromCallable(() -> {
            LineageGraphResult result = sqlLineageParser.parse(request.getSql(), request.getTargetTable());

            for (LineageGraphResult.Edge edge : result.getEdges()) {
                LineageGraph entity = new LineageGraph();
                String[] sourceParts = edge.getSource().split(":")[1].split("\\.");
                String[] targetParts = edge.getTarget().split(":")[1].split("\\.");

                entity.setSourceTable(sourceParts[0]);
                if (sourceParts.length > 1) {
                    entity.setSourceColumn(sourceParts[1]);
                }
                entity.setTargetTable(targetParts[0]);
                if (targetParts.length > 1) {
                    entity.setTargetColumn(targetParts[1]);
                }
                entity.setTransformType(edge.getTransformType());
                entity.setSqlTemplate(request.getSql());
                lineageGraphMapper.insert(entity);
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<LineageGraph> getLineageByTable(String tableName) {
        return Flux.defer(() -> {
            return Flux.fromIterable(lineageGraphMapper.selectList(null))
                    .filter(l -> tableName.equals(l.getSourceTable()) || tableName.equals(l.getTargetTable()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
