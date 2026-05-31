package com.cdcsync.lineage.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageNode {

    private String tableName;

    private String columnName;

    @Builder.Default
    private Set<String> expressions = new HashSet<>();

    public String getQualifiedName() {
        return tableName + "." + columnName;
    }

    public static LineageNode of(String tableName, String columnName) {
        return LineageNode.builder()
                .tableName(tableName)
                .columnName(columnName)
                .build();
    }
}
