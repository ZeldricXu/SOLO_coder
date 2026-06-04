package com.cicd.common.util;

import com.cicd.common.dto.pipeline.PipelineDefinition;
import com.cicd.common.enums.StepType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YamlParserTest {

    @Test
    void testParseSimplePipeline() throws Exception {
        String yaml = """
            name: test-pipeline
            description: Test pipeline
            params:
              - name: branch
                type: string
                default_value: main
            stages:
              - name: build
                jobs:
                  - name: compile
                    steps:
                      - name: maven-build
                        type: SCRIPT
                        script: mvn clean package
            """;

        PipelineDefinition pipeline = YamlParser.parse(yaml);

        assertNotNull(pipeline);
        assertEquals("test-pipeline", pipeline.getName());
        assertEquals("Test pipeline", pipeline.getDescription());
        assertNotNull(pipeline.getParams());
        assertEquals(1, pipeline.getParams().size());
        assertEquals("branch", pipeline.getParams().get(0).getName());
        assertEquals("main", pipeline.getParams().get(0).getDefaultValue());
        assertNotNull(pipeline.getStages());
        assertEquals(1, pipeline.getStages().size());
        assertEquals("build", pipeline.getStages().get(0).getName());
        assertNotNull(pipeline.getStages().get(0).getJobs());
        assertEquals(1, pipeline.getStages().get(0).getJobs().size());
        assertEquals("compile", pipeline.getStages().get(0).getJobs().get(0).getName());
        assertEquals(StepType.SCRIPT, pipeline.getStages().get(0).getJobs().get(0).getSteps().get(0).getType());
    }

    @Test
    void testParsePipelineWithDockerAndDeploy() throws Exception {
        String yaml = """
            name: java-maven-pipeline
            description: Java Maven Build and Deploy
            stages:
              - name: build
                jobs:
                  - name: compile
                    steps:
                      - name: checkout
                        type: SCRIPT
                        run: git clone .
                      - name: maven-build
                        type: SCRIPT
                        script: mvn clean package -DskipTests
                      - name: build-image
                        type: DOCKER
                        docker:
                          image: myapp:latest
                          dockerfile: Dockerfile
                      - name: push-image
                        type: PUSH
                        push:
                          registry: registry.example.com
                      - name: deploy
                        type: DEPLOY
                        deploy:
                          environment: dev
                          strategy: ROLLING
            """;

        PipelineDefinition pipeline = YamlParser.parse(yaml);

        assertNotNull(pipeline);
        assertEquals(5, pipeline.getStages().get(0).getJobs().get(0).getSteps().size());
        assertEquals(StepType.DOCKER, pipeline.getStages().get(0).getJobs().get(0).getSteps().get(2).getType());
        assertEquals(StepType.PUSH, pipeline.getStages().get(0).getJobs().get(0).getSteps().get(3).getType());
        assertEquals(StepType.DEPLOY, pipeline.getStages().get(0).getJobs().get(0).getSteps().get(4).getType());
        assertNotNull(pipeline.getStages().get(0).getJobs().get(0).getSteps().get(2).getDocker());
        assertEquals("myapp:latest", pipeline.getStages().get(0).getJobs().get(0).getSteps().get(2).getDocker().getImage());
    }

    @Test
    void testValidationMissingName() {
        String yaml = """
            description: Pipeline without name
            stages:
              - name: build
                jobs:
                  - name: compile
                    steps:
                      - name: step1
                        type: SCRIPT
            """;

        YamlParser.PipelineValidationException ex = assertThrows(
            YamlParser.PipelineValidationException.class,
            () -> YamlParser.parse(yaml)
        );
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.field().contains("name")));
    }

    @Test
    void testValidationMissingStages() {
        String yaml = """
            name: no-stages-pipeline
            """;

        YamlParser.PipelineValidationException ex = assertThrows(
            YamlParser.PipelineValidationException.class,
            () -> YamlParser.parse(yaml)
        );
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.field().contains("stages")));
    }

    @Test
    void testValidationMissingStepType() {
        String yaml = """
            name: test-pipeline
            stages:
              - name: build
                jobs:
                  - name: compile
                    steps:
                      - name: step-without-type
                        run: echo hello
            """;

        YamlParser.PipelineValidationException ex = assertThrows(
            YamlParser.PipelineValidationException.class,
            () -> YamlParser.parse(yaml)
        );
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.field().contains("type")));
    }

    @Test
    void testValidationEmptyJobs() {
        String yaml = """
            name: test-pipeline
            stages:
              - name: build
                jobs: []
            """;

        YamlParser.PipelineValidationException ex = assertThrows(
            YamlParser.PipelineValidationException.class,
            () -> YamlParser.parse(yaml)
        );
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.field().contains("jobs")));
    }

    @Test
    void testValidationScheduledTriggerMissingCron() {
        String yaml = """
            name: test-pipeline
            trigger:
              schedules:
                - timezone: UTC
            stages:
              - name: build
                jobs:
                  - name: compile
                    steps:
                      - name: step1
                        type: SCRIPT
                        run: echo hello
            """;

        YamlParser.PipelineValidationException ex = assertThrows(
            YamlParser.PipelineValidationException.class,
            () -> YamlParser.parse(yaml)
        );
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.field().contains("cron")));
    }

    @Test
    void testParseInvalidYaml() {
        String invalidYaml = "invalid: yaml: : :";

        assertThrows(YamlParser.PipelineValidationException.class, () -> YamlParser.parse(invalidYaml));
    }

    @Test
    void testParseUnknownProperty() {
        String yaml = """
            name: test-pipeline
            unknown_field: some_value
            stages:
              - name: build
                jobs:
                  - name: compile
                    steps:
                      - name: step1
                        type: SCRIPT
            """;

        assertThrows(YamlParser.PipelineValidationException.class, () -> YamlParser.parse(yaml));
    }

    @Test
    void testDumpPipeline() {
        PipelineDefinition pipeline = new PipelineDefinition();
        pipeline.setName("test");
        pipeline.setDescription("test desc");

        String yaml = YamlParser.dump(pipeline);

        assertNotNull(yaml);
        assertTrue(yaml.contains("name: test"));
        assertTrue(yaml.contains("description: test desc"));
    }

    @Test
    void testSubstituteVariables() {
        String content = "Hello ${name}, your branch is ${branch}";
        Map<String, String> variables = Map.of("name", "World", "branch", "main");

        String result = YamlParser.substituteVariables(content, variables);

        assertEquals("Hello World, your branch is main", result);
    }
}
