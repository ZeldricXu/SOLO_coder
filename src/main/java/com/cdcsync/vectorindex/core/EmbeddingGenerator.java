package com.cdcsync.vectorindex.core;

import java.util.List;

public interface EmbeddingGenerator {

    float[] generate(String text);

    List<float[]> generateBatch(List<String> texts);

    int getDimension();
}
