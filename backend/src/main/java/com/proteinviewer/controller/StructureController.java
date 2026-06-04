package com.proteinviewer.controller;

import com.proteinviewer.dto.*;
import com.proteinviewer.model.ProteinStructure;
import com.proteinviewer.service.CollaborationService;
import com.proteinviewer.service.StructureService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/structures")
@CrossOrigin(origins = "*")
public class StructureController {

    private final StructureService structureService;

    public StructureController(StructureService structureService) {
        this.structureService = structureService;
    }

    @PostMapping("/upload")
    public ResponseEntity<StructureUploadResponse> uploadStructure(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "projectId", defaultValue = "1") Long projectId) {
        return ResponseEntity.ok(structureService.uploadStructure(file, name, projectId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PdbDataDto> getStructureData(@PathVariable Long id) {
        return ResponseEntity.ok(structureService.getStructureData(id));
    }

    @GetMapping(value = "/{id}/binary", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> getStructureBinary(@PathVariable Long id) {
        PdbDataDto structureData = structureService.getStructureData(id);
        byte[] binaryData = BinaryStructureData.toBinaryBytes(structureData);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Atoms-Count", String.valueOf(structureData.getAtoms() != null ? structureData.getAtoms().size() : 0));
        headers.add("X-Bonds-Count", String.valueOf(structureData.getBonds() != null ? structureData.getBonds().size() : 0));
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .headers(headers)
                .body(binaryData);
    }

    @GetMapping
    public ResponseEntity<List<ProteinStructure>> listStructures() {
        return ResponseEntity.ok(structureService.listStructures());
    }

    @GetMapping("/{id}/distance")
    public ResponseEntity<DistanceResultDto> calculateDistance(
            @PathVariable Long id,
            @RequestParam int atom1,
            @RequestParam int atom2) {
        return ResponseEntity.ok(structureService.calculateDistance(id, atom1, atom2));
    }

    @GetMapping("/{id}/angle")
    public ResponseEntity<AngleResultDto> calculateAngle(
            @PathVariable Long id,
            @RequestParam int atom1,
            @RequestParam int atom2,
            @RequestParam int atom3) {
        return ResponseEntity.ok(structureService.calculateAngle(id, atom1, atom2, atom3));
    }

    @GetMapping("/{id}/interactions")
    public ResponseEntity<InteractionResultDto> analyzeInteractions(
            @PathVariable Long id,
            @RequestParam String chainId,
            @RequestParam int resSeq,
            @RequestParam(defaultValue = "5.0") double cutoff) {
        return ResponseEntity.ok(structureService.analyzeInteractions(id, chainId, resSeq, cutoff));
    }

    @PostMapping("/align")
    public ResponseEntity<AlignmentResultDto> alignStructures(
            @RequestParam Long id1,
            @RequestParam Long id2) {
        return ResponseEntity.ok(structureService.alignStructures(id1, id2));
    }

    @GetMapping("/{id}/electrostatic-surface")
    public ResponseEntity<ElectrostaticSurfaceDto> getElectrostaticSurface(@PathVariable Long id) {
        return ResponseEntity.ok(structureService.computeElectrostaticSurface(id));
    }

    @PostMapping("/{id}/electrostatic-surface-async")
    public ResponseEntity<BatchTaskStatusDto> submitElectrostaticSurface(@PathVariable Long id) {
        return ResponseEntity.ok(structureService.submitElectrostaticSurface(id));
    }

    @PostMapping("/batch-analysis")
    public ResponseEntity<BatchAnalysisResultDto> batchAnalysis(@RequestBody List<Long> structureIds) {
        return ResponseEntity.ok(structureService.batchAnalysis(structureIds));
    }

    @PostMapping("/batch-analysis-async")
    public ResponseEntity<BatchTaskStatusDto> submitBatchAnalysis(@RequestBody List<Long> structureIds) {
        return ResponseEntity.ok(structureService.submitBatchAnalysis(structureIds));
    }

    @PostMapping("/align-async")
    public ResponseEntity<BatchTaskStatusDto> submitMultiStructureAlignment(@RequestBody List<Long> structureIds) {
        return ResponseEntity.ok(structureService.submitMultiStructureAlignment(structureIds));
    }

    @GetMapping("/batch-analysis/{taskId}")
    public ResponseEntity<BatchTaskStatusDto> getBatchTaskStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(structureService.getBatchTaskStatus(taskId));
    }

    @GetMapping("/batch-analysis/{taskId}/result")
    public ResponseEntity<BatchAnalysisResultDto> getBatchTaskResult(@PathVariable String taskId) {
        return ResponseEntity.ok(structureService.getBatchTaskResult(taskId));
    }

    @GetMapping("/tasks/active")
    public ResponseEntity<List<BatchTaskStatusDto>> getActiveTasks() {
        return ResponseEntity.ok(structureService.getActiveTasks());
    }
}
