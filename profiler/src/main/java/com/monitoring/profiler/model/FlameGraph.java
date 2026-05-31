package com.monitoring.profiler.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FlameGraph {

    private String name;

    private Long value;

    private String type;

    @Builder.Default
    private List<FlameGraph> children = new ArrayList<>();

    public void addChild(FlameGraph child) {
        children.add(child);
    }
}
