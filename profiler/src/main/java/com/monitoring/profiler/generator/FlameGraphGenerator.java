package com.monitoring.profiler.generator;

import com.monitoring.profiler.model.FlameGraph;
import com.monitoring.profiler.model.ProfileSample;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FlameGraphGenerator {

    public FlameGraph generate(List<ProfileSample> samples) {
        FlameGraph root = FlameGraph.builder()
                .name("root")
                .value(0L)
                .type("root")
                .build();

        Map<String, Map<String, FlameGraph>> nodeCache = new HashMap<>();

        for (ProfileSample sample : samples) {
            if (sample.getStackTrace() == null || sample.getStackTrace().isEmpty()) {
                continue;
            }

            List<ProfileSample.StackFrame> frames = new ArrayList<>(sample.getStackTrace());
            Collections.reverse(frames);

            FlameGraph current = root;
            StringBuilder path = new StringBuilder();

            for (ProfileSample.StackFrame frame : frames) {
                String frameName = frame.getClassName() + "." + frame.getMethodName();
                if (frame.getLineNumber() != null && frame.getLineNumber() > 0) {
                    frameName += ":" + frame.getLineNumber();
                }

                path.append("/").append(frameName);

                Map<String, FlameGraph> children = nodeCache.computeIfAbsent(current.getName(), k -> new HashMap<>());
                FlameGraph child = children.get(frameName);

                if (child == null) {
                    child = FlameGraph.builder()
                            .name(frameName)
                            .value(0L)
                            .type("function")
                            .build();
                    current.addChild(child);
                    children.put(frameName, child);
                }

                child.setValue(child.getValue() + 1);
                current = child;
            }

            root.setValue(root.getValue() + 1);
        }

        return root;
    }

    public FlameGraph generateDiff(FlameGraph before, FlameGraph after) {
        FlameGraph diffRoot = FlameGraph.builder()
                .name("diff")
                .value(after.getValue() - before.getValue())
                .type("diff")
                .build();

        buildDiffTree(before, after, diffRoot);
        return diffRoot;
    }

    private void buildDiffTree(FlameGraph before, FlameGraph after, FlameGraph diffParent) {
        Map<String, FlameGraph> beforeChildren = new HashMap<>();
        Map<String, FlameGraph> afterChildren = new HashMap<>();

        if (before != null && before.getChildren() != null) {
            for (FlameGraph child : before.getChildren()) {
                beforeChildren.put(child.getName(), child);
            }
        }

        if (after != null && after.getChildren() != null) {
            for (FlameGraph child : after.getChildren()) {
                afterChildren.put(child.getName(), child);
            }
        }

        Set<String> allNames = new HashSet<>();
        allNames.addAll(beforeChildren.keySet());
        allNames.addAll(afterChildren.keySet());

        for (String name : allNames) {
            FlameGraph beforeChild = beforeChildren.get(name);
            FlameGraph afterChild = afterChildren.get(name);

            long beforeValue = beforeChild != null ? beforeChild.getValue() : 0;
            long afterValue = afterChild != null ? afterChild.getValue() : 0;

            FlameGraph diffChild = FlameGraph.builder()
                    .name(name)
                    .value(afterValue - beforeValue)
                    .type("diff")
                    .build();

            diffParent.addChild(diffChild);
            buildDiffTree(beforeChild, afterChild, diffChild);
        }
    }

    public String toJson(FlameGraph flameGraph) {
        return com.fasterxml.jackson.databind.ObjectMapperHolder
                .getObjectMapper()
                .tryWriteValueAsString(flameGraph);
    }

    static class ObjectMapperHolder {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static com.fasterxml.jackson.databind.ObjectMapper getObjectMapper() {
            return MAPPER;
        }
    }
}
