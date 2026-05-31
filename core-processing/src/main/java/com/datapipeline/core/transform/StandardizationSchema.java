package com.datapipeline.core.transform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardizationSchema {

    @Builder.Default
    private List<FieldDefinition> fields = new ArrayList<>();

}
