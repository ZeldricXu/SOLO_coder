package com.datapipeline.dp.injector;

import com.datapipeline.dp.noise.NoiseGenerator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldConfig {

    private String fieldPath;
    @Builder.Default
    private NoiseGenerator.Type noiseType = NoiseGenerator.Type.LAPLACE;
    @Builder.Default
    private double epsilon = 0.1;
    @Builder.Default
    private double delta = 1e-5;
    @Builder.Default
    private double sensitivity = 1.0;

}
