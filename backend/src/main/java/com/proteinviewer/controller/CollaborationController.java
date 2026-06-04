package com.proteinviewer.controller;

import com.proteinviewer.dto.AnnotationDto;
import com.proteinviewer.dto.SnapshotDto;
import com.proteinviewer.model.Comment;
import com.proteinviewer.service.CollaborationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/collaboration")
@CrossOrigin(origins = "*")
public class CollaborationController {

    private static final Logger logger = LoggerFactory.getLogger(CollaborationController.class);

    private final CollaborationService collaborationService;

    public CollaborationController(CollaborationService collaborationService) {
        this.collaborationService = collaborationService;
    }

    @PostMapping("/annotations")
    public ResponseEntity<AnnotationDto> createAnnotation(@RequestBody AnnotationDto dto) {
        return ResponseEntity.ok(collaborationService.addAnnotation(dto.getStructureId(), dto));
    }

    @GetMapping("/annotations/{structureId}")
    public ResponseEntity<List<AnnotationDto>> getAnnotations(@PathVariable Long structureId) {
        return ResponseEntity.ok(collaborationService.getAnnotations(structureId));
    }

    @GetMapping("/annotation/{annotationId}")
    public ResponseEntity<AnnotationDto> getAnnotation(@PathVariable Long annotationId) {
        return ResponseEntity.ok(collaborationService.getAnnotation(annotationId));
    }

    @PatchMapping("/annotation/{annotationId}")
    public ResponseEntity<AnnotationDto> patchAnnotation(@PathVariable Long annotationId, @RequestBody AnnotationDto dto) {
        return ResponseEntity.ok(collaborationService.updateAnnotation(annotationId, dto));
    }

    @PutMapping("/annotations/{id}")
    @Deprecated
    public ResponseEntity<AnnotationDto> updateAnnotation(@PathVariable Long id, @RequestBody AnnotationDto dto) {
        logger.warn("DEPRECATED: PUT /api/collaboration/annotations/{id} is deprecated. Use PATCH /api/collaboration/annotation/{id} instead.");
        return ResponseEntity.ok(collaborationService.updateAnnotation(id, dto));
    }

    @DeleteMapping("/annotation/{annotationId}")
    public ResponseEntity<Void> deleteAnnotation(@PathVariable Long annotationId) {
        collaborationService.deleteAnnotation(annotationId);
        return ResponseEntity.ok().build();
    }

    @Deprecated
    @DeleteMapping("/annotations/{id}")
    public ResponseEntity<Void> deleteAnnotationOld(@PathVariable Long id) {
        logger.warn("DEPRECATED: DELETE /api/collaboration/annotations/{id} is deprecated. Use DELETE /api/collaboration/annotation/{id} instead.");
        collaborationService.deleteAnnotation(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/comments")
    public ResponseEntity<Comment> addComment(
            @RequestParam Long structureId,
            @RequestParam String content,
            @RequestParam double x,
            @RequestParam double y,
            @RequestParam double z,
            @RequestParam(defaultValue = "1") Long userId) {
        return ResponseEntity.ok(collaborationService.addComment(structureId, content, x, y, z, userId));
    }

    @GetMapping("/comments/{structureId}")
    public ResponseEntity<List<Comment>> getComments(@PathVariable Long structureId) {
        return ResponseEntity.ok(collaborationService.getComments(structureId));
    }

    @PostMapping("/snapshots")
    public ResponseEntity<SnapshotDto> createSnapshot(@RequestBody SnapshotDto dto) {
        return ResponseEntity.ok(collaborationService.createSnapshot(dto));
    }

    @GetMapping("/snapshots/{shortId}")
    public ResponseEntity<SnapshotDto> getSnapshot(@PathVariable String shortId) {
        SnapshotDto snapshot = collaborationService.getSnapshot(shortId);
        if (snapshot == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(snapshot);
    }
}
