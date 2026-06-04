package com.flowplatform.common.renderer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class RendererRegistry {
    private final Map<String, FieldRenderer> registry = new HashMap<>();

    public RendererRegistry(List<FieldRenderer> renderers) {
        renderers.forEach(r -> registry.put(r.getType(), r));
    }

    public FieldRenderer getRenderer(String type) {
        return registry.getOrDefault(type, registry.get("default"));
    }

    public void register(FieldRenderer renderer) {
        registry.put(renderer.getType(), renderer);
    }

    public Set<String> getSupportedTypes() {
        return registry.keySet();
    }
}
