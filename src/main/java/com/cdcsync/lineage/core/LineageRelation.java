package com.cdcsync.lineage.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageRelation {

    private LineageNode source;

    private LineageNode target;

    private String transformation;

    private String transformationType;
}
