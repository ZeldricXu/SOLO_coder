package com.cicd.server.websocket;

import com.cicd.server.entity.JobExecution;
import com.cicd.server.entity.StepExecution;
import com.cicd.server.repository.JobExecutionRepository;
import com.cicd.server.repository.StepExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogWebSocketService logWebSocketService;
    private final JobExecutionRepository jobExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;

    @GetMapping("/job/{jobId}")
    public ResponseEntity<Map<String, Object>> getJobLogs(@PathVariable Long jobId) {
        String liveLogs = logWebSocketService.getLogBuffer(jobId);

        JobExecution job = jobExecutionRepository.findById(jobId).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        List<StepExecution> steps = stepExecutionRepository.findByJobExecutionId(jobId);
        steps.sort(Comparator.comparingInt(StepExecution::getStepOrder));

        StringBuilder historicalLogs = new StringBuilder();
        for (StepExecution step : steps) {
            if (step.getOutput() != null) {
                historicalLogs.append("=== Step: ").append(step.getStepName()).append(" ===\n");
                historicalLogs.append(step.getOutput());
            }
        }

        String allLogs = historicalLogs + liveLogs;

        return ResponseEntity.ok(Map.of(
            "jobId", jobId,
            "logs", allLogs,
            "status", job.getStatus().name()
        ));
    }

    @DeleteMapping("/job/{jobId}")
    public ResponseEntity<Void> clearLogs(@PathVariable Long jobId) {
        logWebSocketService.clearLogBuffer(jobId);
        return ResponseEntity.noContent().build();
    }
}
