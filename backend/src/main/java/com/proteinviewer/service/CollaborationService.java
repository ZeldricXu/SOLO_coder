package com.proteinviewer.service;

import com.proteinviewer.dto.AnnotationDto;
import com.proteinviewer.dto.AnnotationMessage;
import com.proteinviewer.dto.SnapshotDto;
import com.proteinviewer.exception.AnnotationNotFoundException;
import com.proteinviewer.exception.OptimisticConcurrencyException;
import com.proteinviewer.model.Annotation;
import com.proteinviewer.model.Comment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class CollaborationService {

    private static final Logger logger = LoggerFactory.getLogger(CollaborationService.class);

    private final ConcurrentHashMap<Long, Annotation> annotations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Comment> comments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SnapshotDto> snapshots = new ConcurrentHashMap<>();
    private final AtomicLong annotationIdGen = new AtomicLong(1);
    private final AtomicLong commentIdGen = new AtomicLong(1);

    private SimpMessagingTemplate messagingTemplate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public CollaborationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public CollaborationService() {
        this.messagingTemplate = null;
    }

    public AnnotationDto addAnnotation(Long structureId, AnnotationDto dto) {
        Long id = annotationIdGen.getAndIncrement();
        LocalDateTime now = LocalDateTime.now();
        Annotation annotation = Annotation.builder()
                .id(id)
                .structureId(structureId)
                .type(dto.getType())
                .label(dto.getLabel())
                .description(dto.getDescription())
                .shapeData(dto.getShapeData())
                .positionX(dto.getPositionX())
                .positionY(dto.getPositionY())
                .positionZ(dto.getPositionZ())
                .color(dto.getColor() != null ? dto.getColor() : "#FFD700")
                .visible(dto.getVisible() != null ? dto.getVisible() : true)
                .createdBy(dto.getCreatedBy())
                .createdAt(now)
                .version(0)
                .build();
        annotations.put(id, annotation);

        AnnotationDto result = toDto(annotation);
        broadcastAnnotationChange(AnnotationMessage.OperationType.CREATED, result, structureId);
        return result;
    }

    @Deprecated
    public AnnotationDto createAnnotation(AnnotationDto dto) {
        logger.warn("DEPRECATED: createAnnotation() is deprecated. Use addAnnotation() instead.");
        return addAnnotation(dto.getStructureId(), dto);
    }

    public AnnotationDto getAnnotation(Long annotationId) {
        Annotation annotation = annotations.get(annotationId);
        if (annotation == null) {
            throw new AnnotationNotFoundException(annotationId);
        }
        return toDto(annotation);
    }

    public List<AnnotationDto> getAnnotations(Long structureId) {
        return annotations.values().stream()
                .filter(a -> a.getStructureId().equals(structureId))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public AnnotationDto updateAnnotation(Long annotationId, AnnotationDto dto) {
        Annotation existing = annotations.get(annotationId);
        if (existing == null) {
            throw new AnnotationNotFoundException(annotationId);
        }

        if (dto.getVersion() != null && !dto.getVersion().equals(existing.getVersion())) {
            throw new OptimisticConcurrencyException(
                    "Version mismatch for annotation " + annotationId +
                    ". Expected version " + existing.getVersion() +
                    ", but got version " + dto.getVersion()
            );
        }

        synchronized (existing) {
            if (dto.getLabel() != null) existing.setLabel(dto.getLabel());
            if (dto.getDescription() != null) existing.setDescription(dto.getDescription());
            if (dto.getShapeData() != null) existing.setShapeData(dto.getShapeData());
            if (dto.getColor() != null) existing.setColor(dto.getColor());
            if (dto.getVisible() != null) existing.setVisible(dto.getVisible());
            if (dto.getPositionX() != null) existing.setPositionX(dto.getPositionX());
            if (dto.getPositionY() != null) existing.setPositionY(dto.getPositionY());
            if (dto.getPositionZ() != null) existing.setPositionZ(dto.getPositionZ());
            if (dto.getType() != null) existing.setType(dto.getType());
            existing.setUpdatedAt(LocalDateTime.now());
            existing.setVersion(existing.getVersion() + 1);
        }

        AnnotationDto result = toDto(existing);
        broadcastAnnotationChange(AnnotationMessage.OperationType.UPDATED, result, existing.getStructureId());
        return result;
    }

    public void deleteAnnotation(Long annotationId) {
        Annotation annotation = annotations.remove(annotationId);
        if (annotation != null) {
            AnnotationDto dto = toDto(annotation);
            broadcastAnnotationChange(AnnotationMessage.OperationType.DELETED, dto, annotation.getStructureId());
        }
    }

    private void broadcastAnnotationChange(AnnotationMessage.OperationType operation, AnnotationDto annotation, Long structureId) {
        if (messagingTemplate != null) {
            try {
                AnnotationMessage message = AnnotationMessage.builder()
                        .operation(operation)
                        .annotation(annotation)
                        .structureId(structureId)
                        .build();
                messagingTemplate.convertAndSend("/topic/annotations/" + structureId, message);
            } catch (Exception e) {
                logger.warn("Failed to broadcast annotation change: {}", e.getMessage());
            }
        }
    }

    private AnnotationDto toDto(Annotation annotation) {
        return AnnotationDto.builder()
                .id(annotation.getId())
                .structureId(annotation.getStructureId())
                .type(annotation.getType())
                .label(annotation.getLabel())
                .description(annotation.getDescription())
                .shapeData(annotation.getShapeData())
                .positionX(annotation.getPositionX())
                .positionY(annotation.getPositionY())
                .positionZ(annotation.getPositionZ())
                .color(annotation.getColor())
                .visible(annotation.getVisible())
                .createdBy(annotation.getCreatedBy())
                .createdAt(annotation.getCreatedAt())
                .updatedAt(annotation.getUpdatedAt())
                .version(annotation.getVersion())
                .build();
    }

    public Comment addComment(Long structureId, String content, double x, double y, double z, Long userId) {
        Long id = commentIdGen.getAndIncrement();
        Comment comment = Comment.builder()
                .id(id)
                .structureId(structureId)
                .content(content)
                .anchorX(x).anchorY(y).anchorZ(z)
                .userId(userId)
                .build();
        comments.put(id, comment);
        return comment;
    }

    public List<Comment> getComments(Long structureId) {
        return comments.values().stream()
                .filter(c -> c.getStructureId().equals(structureId))
                .collect(Collectors.toList());
    }

    public SnapshotDto createSnapshot(SnapshotDto dto) {
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        dto.setShortId(shortId);
        dto.setCreatedAt(java.time.LocalDateTime.now().toString());
        snapshots.put(shortId, dto);
        return dto;
    }

    public SnapshotDto getSnapshot(String shortId) {
        return snapshots.get(shortId);
    }
}
