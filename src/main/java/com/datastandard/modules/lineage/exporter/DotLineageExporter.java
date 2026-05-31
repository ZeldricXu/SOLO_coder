package com.datastandard.modules.lineage.exporter;

import com.datastandard.modules.lineage.model.LineageEdge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DotLineageExporter implements LineageExporter {

    @Override
    public void export(List<LineageEdge> edges, String path) {
        log.info("导出DOT格式血缘数据到: {}", path);
    }

    @Override
    public String getFormat() {
        return "DOT";
    }
}
