package com.datapipeline.core.transform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinition {

    public enum FieldType {
        STRING,
        INTEGER,
        LONG,
        DOUBLE,
        BOOLEAN,
        OBJECT,
        ARRAY
    }

    private String name;
    private String path;
    @Builder.Default
    private FieldType type = FieldType.STRING;
    @Builder.Default
    private boolean required = false;

}
