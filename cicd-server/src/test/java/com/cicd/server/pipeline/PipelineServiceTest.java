package com.cicd.server.pipeline;

import com.cicd.common.dto.pipeline.PipelineDefinition;
import com.cicd.common.enums.PipelineStatus;
import com.cicd.common.util.YamlParser;
import com.cicd.server.entity.Pipeline;
import com.cicd.server.entity.PipelineExecution;
import com.cicd.server.entity.Project;
import com.cicd.server.repository.PipelineExecutionRepository;
import com.cicd.server.repository.PipelineRepository;
import com.cicd.server.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @Mock
    private PipelineRepository pipelineRepository;

    @Mock
    private PipelineExecutionRepository executionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private PipelineOrchestrator orchestrator;

    @InjectMocks
    private PipelineService pipelineService;

    private Pipeline testPipeline;
    private Project testProject;
    private String testYaml;

    @BeforeEach
    void setUp() {
        testProject = new Project();
        testProject.setId(1L);
        testProject.setName("test-project");

        testYaml = """
            name: test-pipeline
            description: Test pipeline
            params:
              - name: branch
                type: string
                default: main
            stages:
              - name: build
        jobs:
          - name: compile
            steps:
              - name: build
                type: script
                script: mvn clean package
        """;

        testPipeline = new Pipeline();
        testPipeline.setId(1L);
        testPipeline.setName("test-pipeline");
        testPipeline.setProject(testProject);
        testPipeline.setYamlContent(testYaml);
    }

    @Test
    void testCreatePipeline() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(testProject));
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(testPipeline);

        Pipeline result = pipelineService.createPipeline(1L, testYaml);

        assertNotNull(result);
        assertEquals("test-pipeline", result.getName());
        verify(pipelineRepository, times(1)).save(any(Pipeline.class));
    }

    @Test
    void testCreatePipelineProjectNotFound() {
        when(projectRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> pipelineService.createPipeline(999L, testYaml));
        verify(pipelineRepository, never()).save(any(Pipeline.class));
    }

    @Test
    void testValidatePipelineYaml() {
        boolean result = pipelineService.validatePipelineYaml(testYaml);

        assertTrue(result);
    }

    @Test
    void testValidatePipelineYamlInvalid() {
        String invalidYaml = "invalid: yaml: : :";

        boolean result = pipelineService.validatePipelineYaml(invalidYaml);

        assertFalse(result);
    }

    @Test
    void testTriggerPipeline() {
        Map<String, String> params = new HashMap<>();
        params.put("branch", "main");
        params.put("environment", "dev");

        PipelineExecution execution = new PipelineExecution();
        execution.setId(1L);
        execution.setPipeline(testPipeline);
        execution.setStatus(PipelineStatus.PENDING);

        when(pipelineRepository.findById(1L)).thenReturn(Optional.of(testPipeline));
        when(executionRepository.save(any(PipelineExecution.class))).thenReturn(execution);
        when(orchestrator.orchestrate(any(PipelineExecution.class), anyMap())).thenReturn(execution);

        PipelineExecution result = pipelineService.triggerPipeline(1L, params, "test-user");

        assertNotNull(result);
        assertEquals(PipelineStatus.PENDING, result.getStatus());
        verify(orchestrator, times(1)).orchestrate(any(PipelineExecution.class), anyMap());
    }

    @Test
    void testTriggerPipelineNotFound() {
        when(pipelineRepository.findById(999L)).thenReturn(Optional.empty());

        Map<String, String> params = new HashMap<>();
        assertThrows(RuntimeException.class, () -> pipelineService.triggerPipeline(999L, params, "test-user"));
    }

    @Test
    void testParseYaml() {
        PipelineDefinition definition = pipelineService.parseYaml(testYaml);

        assertNotNull(definition);
        assertEquals("test-pipeline", definition.getName());
        assertEquals(1, definition.getParams().size());
        assertEquals(1, definition.getStages().size());
    }

    @Test
    void testGetPipelineDefinition() {
        when(pipelineRepository.findById(1L)).thenReturn(Optional.of(testPipeline));

        PipelineDefinition definition = pipelineService.getPipelineDefinition(1L);

        assertNotNull(definition);
        assertEquals("test-pipeline", definition.getName());
    }

    @Test
    void testGetPipelineDefinitionNotFound() {
        when(pipelineRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> pipelineService.getPipelineDefinition(999L));
    }

    @Test
    void testUpdatePipeline() {
        String updatedYaml = """
            name: updated-pipeline
            description: Updated pipeline
            stages:
              - name: build
        jobs:
          - name: compile
            steps:
              - name: build
                type: script
                script: mvn clean install
        """;

        when(pipelineRepository.findById(1L)).thenReturn(Optional.of(testPipeline));
        when(pipelineRepository.save(any(Pipeline.class))).thenReturn(testPipeline);

        Pipeline result = pipelineService.updatePipeline(1L, updatedYaml);

        assertNotNull(result);
        verify(pipelineRepository, times(1)).save(any(Pipeline.class));
    }

    @Test
    void testDeletePipeline() {
        when(pipelineRepository.findById(1L)).thenReturn(Optional.of(testPipeline));
        doNothing().when(pipelineRepository).delete(testPipeline);

        assertDoesNotThrow(() -> pipelineService.deletePipeline(1L));
        verify(pipelineRepository, times(1)).delete(testPipeline);
    }

    @Test
    void testDeletePipelineNotFound() {
        when(pipelineRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> pipelineService.deletePipeline(999L));
        verify(pipelineRepository, never()).delete(any(Pipeline.class));
    }
}
