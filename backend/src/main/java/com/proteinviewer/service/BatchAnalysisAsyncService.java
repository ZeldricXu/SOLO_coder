package com.proteinviewer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proteinviewer.dto.AlignmentResultDto;
import com.proteinviewer.dto.BatchAnalysisResultDto;
import com.proteinviewer.dto.BatchTaskStatusDto;
import com.proteinviewer.model.BatchTask;
import com.proteinviewer.model.ParsedPdb;
import com.proteinviewer.model.TaskType;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BatchAnalysisAsyncService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BatchAnalysisAsyncService.class);

    private final MolecularAnalysisService analysisService;
    private final TaskQueueManager taskQueueManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private StructureService structureService;

    public BatchAnalysisAsyncService(MolecularAnalysisService analysisService, TaskQueueManager taskQueueManager) {
        this.analysisService = analysisService;
        this.taskQueueManager = taskQueueManager;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setStructureService(@org.springframework.context.annotation.Lazy StructureService structureService) {
        this.structureService = structureService;
    }

    @PostConstruct
    public void init() {
        taskQueueManager.setTaskRecreator(this::recreateTaskRunnable);
    }

    private Runnable recreateTaskRunnable(BatchTask task) {
        try {
            String inputJson = task.getInputJson();
            if (inputJson == null) {
                log.warn("Cannot recreate task {}: inputJson is null", task.getTaskId());
                return null;
            }

            Map<String, Object> input = objectMapper.readValue(inputJson, new TypeReference<Map<String, Object>>() {});
            String taskType = (String) input.get("taskType");
            List<Long> structureIds = ((List<Integer>) input.get("structureIds")).stream()
                    .map(Long::valueOf)
                    .collect(Collectors.toList());

            Function<Long, ParsedPdb> pdbProvider = structureService::getParsedPdbDirect;
            Function<Long, String> nameProvider = id -> structureService.getStructureName(id);

            switch (TaskType.valueOf(taskType)) {
                case BATCH_ANALYSIS:
                    return () -> executeBatch(task.getTaskId(), structureIds, pdbProvider, nameProvider);
                case ELECTROSTATIC_SURFACE:
                    Long structureId = structureIds.isEmpty() ? null : structureIds.get(0);
                    return () -> executeElectrostaticSurface(task.getTaskId(), structureId, pdbProvider);
                case MULTI_STRUCTURE_ALIGNMENT:
                    return () -> executeMultiStructureAlignment(task.getTaskId(), structureIds, pdbProvider);
                default:
                    log.warn("Unknown task type: {}", taskType);
                    return null;
            }
        } catch (Exception e) {
            log.error("Failed to recreate task runnable for task {}", task.getTaskId(), e);
            return null;
        }
    }

    public BatchTaskStatusDto submitBatch(List<Long> structureIds,
                                          Function<Long, ParsedPdb> pdbProvider,
                                          Function<Long, String> nameProvider) {
        String inputJson = createInputJson(TaskType.BATCH_ANALYSIS, structureIds);
        BatchTask task = taskQueueManager.submitTask(
                TaskType.BATCH_ANALYSIS,
                structureIds.size(),
                () -> executeBatch(null, structureIds, pdbProvider, nameProvider),
                1L,
                inputJson
        );
        updateTaskId(task, structureIds, pdbProvider, nameProvider);
        return taskQueueManager.toStatusDto(task);
    }

    private String createInputJson(TaskType taskType, List<Long> structureIds) {
        try {
            Map<String, Object> input = new HashMap<>();
            input.put("taskType", taskType.name());
            input.put("structureIds", structureIds);
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            log.error("Failed to create input JSON", e);
            return null;
        }
    }

    private void updateTaskId(BatchTask task, List<Long> structureIds,
                              Function<Long, ParsedPdb> pdbProvider,
                              Function<Long, String> nameProvider) {
        task.setTaskRunnable(() -> executeBatch(task.getTaskId(), structureIds, pdbProvider, nameProvider));
    }

    private void executeBatch(String taskId, List<Long> structureIds,
                              Function<Long, ParsedPdb> pdbProvider,
                              Function<Long, String> nameProvider) {
        try {
            int n = structureIds.size();
            double[][] rmsdMatrix = new double[n][n];
            List<String> names = new ArrayList<>();
            List<BatchAnalysisResultDto.DisulfideBond> allDisulfide = new ArrayList<>();
            List<BatchAnalysisResultDto.GlycosylationSite> allGlyco = new ArrayList<>();
            Map<Long, BatchAnalysisResultDto.BFactorStats> bfactorMap = new HashMap<>();

            for (int i = 0; i < n; i++) {
                ParsedPdb pdb1 = pdbProvider.apply(structureIds.get(i));
                String name = nameProvider.apply(structureIds.get(i));
                names.add(name != null ? name : "Structure " + structureIds.get(i));
                rmsdMatrix[i][i] = 0.0;

                allDisulfide.addAll(analysisService.detectDisulfideBonds(pdb1, structureIds.get(i)));
                allGlyco.addAll(analysisService.predictGlycosylationSites(pdb1, structureIds.get(i)));
                bfactorMap.put(structureIds.get(i), analysisService.analyzeBFactor(pdb1, structureIds.get(i)));

                for (int j = i + 1; j < n; j++) {
                    ParsedPdb pdb2 = pdbProvider.apply(structureIds.get(j));
                    try {
                        AlignmentResultDto alignment = analysisService.alignStructures(pdb1, pdb2);
                        rmsdMatrix[i][j] = alignment.getRmsd();
                        rmsdMatrix[j][i] = alignment.getRmsd();
                    } catch (Exception e) {
                        rmsdMatrix[i][j] = -1;
                        rmsdMatrix[j][i] = -1;
                    }
                }

                if (taskId != null) {
                    taskQueueManager.updateTaskProgress(taskId, i + 1);
                }
            }

            BatchAnalysisResultDto result = BatchAnalysisResultDto.builder()
                    .taskId(taskId)
                    .status("COMPLETED")
                    .structureIds(structureIds)
                    .rmsdMatrix(rmsdMatrix)
                    .structureNames(names)
                    .disulfideBonds(allDisulfide)
                    .glycosylationSites(allGlyco)
                    .bfactorStats(bfactorMap)
                    .build();

            if (taskId != null) {
                taskQueueManager.markTaskComplete(taskId, "batch-results/" + taskId + ".json", result);
            }

        } catch (Exception e) {
            if (taskId != null) {
                taskQueueManager.markTaskFailed(taskId, e.getMessage());
            }
            log.error("Batch task {} failed", taskId, e);
            throw new RuntimeException(e);
        }
    }

    public BatchTaskStatusDto getTaskStatus(String taskId) {
        BatchTask task = taskQueueManager.getTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return taskQueueManager.toStatusDto(task);
    }

    public BatchAnalysisResultDto getTaskResult(String taskId) {
        Object result = taskQueueManager.getResult(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Result not found for task: " + taskId));
        if (result instanceof BatchAnalysisResultDto) {
            return (BatchAnalysisResultDto) result;
        }
        throw new IllegalArgumentException("Invalid result type for task: " + taskId);
    }

    public List<BatchTaskStatusDto> getActiveTasks() {
        return taskQueueManager.getActiveTasks();
    }

    public BatchTaskStatusDto submitElectrostaticSurface(Long structureId,
                                                         Function<Long, ParsedPdb> pdbProvider) {
        String inputJson = createInputJson(TaskType.ELECTROSTATIC_SURFACE, Collections.singletonList(structureId));
        BatchTask task = taskQueueManager.submitTask(
                TaskType.ELECTROSTATIC_SURFACE,
                1,
                null,
                1L,
                inputJson
        );
        final String taskId = task.getTaskId();
        task.setTaskRunnable(() -> executeElectrostaticSurface(taskId, structureId, pdbProvider));
        return taskQueueManager.toStatusDto(task);
    }

    private void executeElectrostaticSurface(String taskId, Long structureId,
                                             Function<Long, ParsedPdb> pdbProvider) {
        try {
            ParsedPdb pdb = pdbProvider.apply(structureId);
            analysisService.computeElectrostaticSurface(pdb, structureId);
            taskQueueManager.markTaskComplete(taskId, "surface-results/" + taskId + ".json", null);
        } catch (Exception e) {
            taskQueueManager.markTaskFailed(taskId, e.getMessage());
            log.error("Electrostatic surface task {} failed", taskId, e);
            throw new RuntimeException(e);
        }
    }

    public BatchTaskStatusDto submitMultiStructureAlignment(List<Long> structureIds,
                                                            Function<Long, ParsedPdb> pdbProvider) {
        String inputJson = createInputJson(TaskType.MULTI_STRUCTURE_ALIGNMENT, structureIds);
        BatchTask task = taskQueueManager.submitTask(
                TaskType.MULTI_STRUCTURE_ALIGNMENT,
                structureIds.size(),
                null,
                1L,
                inputJson
        );
        final String taskId = task.getTaskId();
        task.setTaskRunnable(() -> executeMultiStructureAlignment(taskId, structureIds, pdbProvider));
        return taskQueueManager.toStatusDto(task);
    }

    private void executeMultiStructureAlignment(String taskId, List<Long> structureIds,
                                                Function<Long, ParsedPdb> pdbProvider) {
        try {
            for (int i = 0; i < structureIds.size(); i++) {
                taskQueueManager.updateTaskProgress(taskId, i + 1);
            }
            taskQueueManager.markTaskComplete(taskId, "alignment-results/" + taskId + ".json", null);
        } catch (Exception e) {
            taskQueueManager.markTaskFailed(taskId, e.getMessage());
            log.error("Multi-structure alignment task {} failed", taskId, e);
            throw new RuntimeException(e);
        }
    }
}
