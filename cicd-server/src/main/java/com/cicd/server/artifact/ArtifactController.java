package com.cicd.server.artifact;

import com.cicd.common.enums.ArtifactType;
import com.cicd.server.entity.Artifact;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/artifacts")
@RequiredArgsConstructor
public class ArtifactController {

    private final ArtifactService artifactService;

    @GetMapping
    public ResponseEntity<Page<Artifact>> listArtifacts(
            @RequestParam Long projectId,
            @RequestParam(required = false) ArtifactType type,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(artifactService.listArtifacts(projectId, type, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Artifact> getArtifact(@PathVariable Long id) {
        Artifact artifact = artifactService.getArtifact(id);
        return artifact != null ? ResponseEntity.ok(artifact) : ResponseEntity.notFound().build();
    }

    @GetMapping("/trace")
    public ResponseEntity<Artifact> traceArtifact(
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String commitSha,
            @RequestParam Long projectId) {
        Artifact artifact;
        if (commitSha != null) {
            artifact = artifactService.traceByCommit(commitSha);
        } else {
            artifact = artifactService.traceArtifact(version, name, projectId);
        }
        return artifact != null ? ResponseEntity.ok(artifact) : ResponseEntity.notFound().build();
    }

    @GetMapping("/{name}/history")
    public ResponseEntity<List<Artifact>> getArtifactHistory(
            @PathVariable String name,
            @RequestParam Long projectId,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(artifactService.getArtifactHistory(projectId, name, limit));
    }

    @PostMapping("/{id}/pin")
    public ResponseEntity<Void> pinArtifact(@PathVariable Long id) {
        artifactService.pinArtifact(id, true);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/pin")
    public ResponseEntity<Void> unpinArtifact(@PathVariable Long id) {
        artifactService.pinArtifact(id, false);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteArtifact(@PathVariable Long id) {
        artifactService.deleteArtifact(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cleanup")
    public ResponseEntity<Void> triggerCleanup() {
        artifactService.cleanupExpiredArtifacts();
        return ResponseEntity.ok().build();
    }
}
