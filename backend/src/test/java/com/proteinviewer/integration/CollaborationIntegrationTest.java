package com.proteinviewer.integration;

import com.proteinviewer.dto.AnnotationDto;
import com.proteinviewer.dto.SnapshotDto;
import com.proteinviewer.exception.OptimisticConcurrencyException;
import com.proteinviewer.model.Comment;
import com.proteinviewer.service.CollaborationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollaborationIntegrationTest {

    @Autowired
    private CollaborationService collaborationService;

    private static final Long STRUCTURE_ID = 1L;
    private static Long annotationId;
    private static String snapshotShortId;

    @Test
    @Order(1)
    @DisplayName("User adds annotation → annotation stored and retrievable")
    void addAnnotation() {
        AnnotationDto dto = AnnotationDto.builder()
                .structureId(STRUCTURE_ID)
                .type("domain")
                .label("Kinase Domain")
                .description("Catalytic kinase domain residues 50-300")
                .positionX(5.0).positionY(5.0).positionZ(5.0)
                .color("#4CAF50")
                .visible(true)
                .createdBy(1L)
                .build();

        AnnotationDto result = collaborationService.createAnnotation(dto);

        assertNotNull(result.getId());
        annotationId = result.getId();
        assertEquals("domain", result.getType());
        assertEquals("Kinase Domain", result.getLabel());
        assertEquals("#4CAF50", result.getColor());
        assertTrue(result.getVisible());
    }

    @Test
    @Order(2)
    @DisplayName("Annotations for a structure are listed correctly")
    void listAnnotations() {
        List<AnnotationDto> annotations = collaborationService.getAnnotations(STRUCTURE_ID);

        assertFalse(annotations.isEmpty());
        assertTrue(annotations.stream().anyMatch(a -> "Kinase Domain".equals(a.getLabel())));
    }

    @Test
    @Order(3)
    @DisplayName("User updates annotation visibility (show/hide toggle)")
    void updateAnnotationVisibility() {
        Assumptions.assumeTrue(annotationId != null);

        AnnotationDto update = AnnotationDto.builder()
                .visible(false)
                .build();

        AnnotationDto result = collaborationService.updateAnnotation(annotationId, update);

        assertFalse(result.getVisible());
    }

    @Test
    @Order(4)
    @DisplayName("User adds mutation site annotation")
    void addMutationAnnotation() {
        AnnotationDto dto = AnnotationDto.builder()
                .structureId(STRUCTURE_ID)
                .type("mutation")
                .label("R273H Mutation")
                .description("Common cancer mutation in DNA binding domain")
                .positionX(8.0).positionY(3.0).positionZ(-1.0)
                .color("#FF5722")
                .visible(true)
                .createdBy(2L)
                .build();

        AnnotationDto result = collaborationService.createAnnotation(dto);

        assertNotNull(result.getId());
        assertEquals("mutation", result.getType());
    }

    @Test
    @Order(5)
    @DisplayName("User adds binding pocket annotation")
    void addBindingPocketAnnotation() {
        AnnotationDto dto = AnnotationDto.builder()
                .structureId(STRUCTURE_ID)
                .type("pocket")
                .label("ATP Binding Pocket")
                .description("ATP binding site near residues 100-120")
                .positionX(3.0).positionY(7.0).positionZ(2.0)
                .color("#2196F3")
                .visible(true)
                .createdBy(1L)
                .build();

        AnnotationDto result = collaborationService.createAnnotation(dto);
        assertNotNull(result.getId());
    }

    @Test
    @Order(6)
    @DisplayName("Annotations filter by type works")
    void annotationsFilterByType() {
        List<AnnotationDto> allAnnotations = collaborationService.getAnnotations(STRUCTURE_ID);

        long domainCount = allAnnotations.stream().filter(a -> "domain".equals(a.getType())).count();
        long mutationCount = allAnnotations.stream().filter(a -> "mutation".equals(a.getType())).count();
        long pocketCount = allAnnotations.stream().filter(a -> "pocket".equals(a.getType())).count();

        assertTrue(domainCount >= 1, "Should have at least 1 domain annotation");
        assertTrue(mutationCount >= 1, "Should have at least 1 mutation annotation");
        assertTrue(pocketCount >= 1, "Should have at least 1 pocket annotation");
    }

    @Test
    @Order(7)
    @DisplayName("User generates share snapshot with camera state")
    void generateShareSnapshot() {
        SnapshotDto snapshot = SnapshotDto.builder()
                .structureId(STRUCTURE_ID)
                .cameraPositionX(40.0)
                .cameraPositionY(30.0)
                .cameraPositionZ(50.0)
                .cameraTargetX(5.0)
                .cameraTargetY(5.0)
                .cameraTargetZ(5.0)
                .cameraUpX(0.0)
                .cameraUpY(1.0)
                .cameraUpZ(0.0)
                .renderMode("ball-stick")
                .colorScheme("element")
                .build();

        SnapshotDto result = collaborationService.createSnapshot(snapshot);

        assertNotNull(result.getShortId());
        assertNotNull(result.getCreatedAt());
        snapshotShortId = result.getShortId();
    }

    @Test
    @Order(8)
    @DisplayName("Another user opens snapshot → sees same camera state and annotations")
    void openSharedSnapshot() {
        Assumptions.assumeTrue(snapshotShortId != null);

        SnapshotDto loaded = collaborationService.getSnapshot(snapshotShortId);

        assertNotNull(loaded);
        assertEquals(STRUCTURE_ID, loaded.getStructureId());
        assertEquals(40.0, loaded.getCameraPositionX(), 0.01);
        assertEquals(30.0, loaded.getCameraPositionY(), 0.01);
        assertEquals(50.0, loaded.getCameraPositionZ(), 0.01);
        assertEquals(5.0, loaded.getCameraTargetX(), 0.01);
        assertEquals("ball-stick", loaded.getRenderMode());
        assertEquals("element", loaded.getColorScheme());

        List<AnnotationDto> annotations = collaborationService.getAnnotations(loaded.getStructureId());
        assertFalse(annotations.isEmpty(), "Viewer should see annotations on shared structure");
    }

    @Test
    @Order(9)
    @DisplayName("User adds comment anchored to 3D coordinate")
    void addCommentAnchoredToCoordinate() {
        Comment comment = collaborationService.addComment(
                STRUCTURE_ID, "The LYS-ASP salt bridge here is critical for stability", 6.0, 1.0, -1.0, 1L
        );

        assertNotNull(comment.getId());
        assertEquals("The LYS-ASP salt bridge here is critical for stability", comment.getContent());
        assertEquals(6.0, comment.getAnchorX(), 0.01);
        assertEquals(1.0, comment.getAnchorY(), 0.01);
        assertEquals(-1.0, comment.getAnchorZ(), 0.01);
        assertEquals(1L, comment.getUserId());
    }

    @Test
    @Order(10)
    @DisplayName("Another user sees comments at the same 3D location")
    void anotherUserSeesComments() {
        List<Comment> comments = collaborationService.getComments(STRUCTURE_ID);

        assertFalse(comments.isEmpty());
        Comment firstComment = comments.get(0);
        assertNotNull(firstComment.getContent());
        assertTrue(firstComment.getAnchorX() != 0 || firstComment.getAnchorY() != 0 || firstComment.getAnchorZ() != 0);
    }

    @Test
    @Order(11)
    @DisplayName("Second user adds reply comment at same location")
    void secondUserReplies() {
        Comment reply = collaborationService.addComment(
                STRUCTURE_ID, "Agreed, also notice the hydrogen bond network", 6.0, 1.0, -1.0, 2L
        );

        assertNotNull(reply.getId());
        assertEquals(2L, reply.getUserId());

        List<Comment> comments = collaborationService.getComments(STRUCTURE_ID);
        long userCount = comments.stream().mapToLong(Comment::getUserId).distinct().count();
        assertTrue(userCount >= 2, "Should have comments from at least 2 users");
    }

    @Test
    @Order(12)
    @DisplayName("Delete annotation removes it from listing")
    void deleteAnnotation() {
        Assumptions.assumeTrue(annotationId != null);

        int countBefore = collaborationService.getAnnotations(STRUCTURE_ID).size();
        collaborationService.deleteAnnotation(annotationId);
        int countAfter = collaborationService.getAnnotations(STRUCTURE_ID).size();

        assertEquals(countBefore - 1, countAfter);
        assertFalse(collaborationService.getAnnotations(STRUCTURE_ID).stream()
                .anyMatch(a -> a.getId().equals(annotationId)));
    }

    @Test
    @Order(13)
    @DisplayName("Annotations for different structure are isolated")
    void annotationsIsolatedByStructure() {
        AnnotationDto otherDto = AnnotationDto.builder()
                .structureId(999L)
                .type("domain")
                .label("Other Structure Domain")
                .positionX(0.0).positionY(0.0).positionZ(0.0)
                .build();
        collaborationService.createAnnotation(otherDto);

        List<AnnotationDto> struct1Annotations = collaborationService.getAnnotations(STRUCTURE_ID);
        List<AnnotationDto> otherAnnotations = collaborationService.getAnnotations(999L);

        assertTrue(struct1Annotations.stream().noneMatch(a -> "Other Structure Domain".equals(a.getLabel())));
        assertTrue(otherAnnotations.stream().anyMatch(a -> "Other Structure Domain".equals(a.getLabel())));
    }

    @Test
    @Order(14)
    @DisplayName("Concurrent annotation creation - no overwrites, all annotations preserved")
    void concurrentAnnotationCreation() throws InterruptedException {
        final Long concurrentStructureId = 100L;
        final int threadCount = 10;
        final int annotationsPerThread = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Exception> exceptions = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadNum = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < annotationsPerThread; i++) {
                        AnnotationDto dto = AnnotationDto.builder()
                                .structureId(concurrentStructureId)
                                .type("domain")
                                .label("Annotation T" + threadNum + "-A" + i)
                                .positionX((double) threadNum)
                                .positionY((double) i)
                                .positionZ(0.0)
                                .createdBy((long) threadNum)
                                .build();
                        collaborationService.addAnnotation(concurrentStructureId, dto);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        assertTrue(exceptions.isEmpty(), "No exceptions should occur during concurrent creation: " + exceptions);

        List<AnnotationDto> allAnnotations = collaborationService.getAnnotations(concurrentStructureId);
        assertEquals(threadCount * annotationsPerThread, allAnnotations.size(),
                "All concurrent annotations should be preserved without overwrites");
    }

    @Test
    @Order(15)
    @DisplayName("Optimistic concurrency prevents stale updates - version mismatch throws exception")
    void optimisticConcurrencyPreventsLostUpdates() {
        Long concurrencyStructId = 200L;
        AnnotationDto dto = AnnotationDto.builder()
                .structureId(concurrencyStructId)
                .type("mutation")
                .label("G12V Mutation")
                .positionX(1.0).positionY(2.0).positionZ(3.0)
                .createdBy(1L)
                .build();

        AnnotationDto created = collaborationService.addAnnotation(concurrencyStructId, dto);
        assertNotNull(created.getId());
        assertEquals(Integer.valueOf(0), created.getVersion(), "Initial version should be 0");

        AnnotationDto update1 = AnnotationDto.builder()
                .label("G12V Mutation (User 1 Edit)")
                .version(0)
                .build();
        AnnotationDto result1 = collaborationService.updateAnnotation(created.getId(), update1);
        assertEquals(Integer.valueOf(1), result1.getVersion(), "Version should increment to 1 after update");

        AnnotationDto staleUpdate = AnnotationDto.builder()
                .label("Stale Update from old version")
                .version(0)
                .build();

        assertThrows(OptimisticConcurrencyException.class, () -> {
            collaborationService.updateAnnotation(created.getId(), staleUpdate);
        }, "Update with stale version should throw exception");

        AnnotationDto current = collaborationService.getAnnotation(created.getId());
        assertEquals("G12V Mutation (User 1 Edit)", current.getLabel(),
                "The label should remain as the successful update, not overwritten by stale update");
        assertEquals(Integer.valueOf(1), current.getVersion(), "Version should remain at 1");
    }

    @Test
    @Order(16)
    @DisplayName("Get single annotation returns all fields including version and timestamps")
    void getSingleAnnotationWithVersionAndTimestamps() {
        Long testStructId = 300L;
        AnnotationDto dto = AnnotationDto.builder()
                .structureId(testStructId)
                .type("domain")
                .label("Test Domain")
                .positionX(1.0).positionY(1.0).positionZ(1.0)
                .createdBy(1L)
                .build();

        AnnotationDto created = collaborationService.addAnnotation(testStructId, dto);

        AnnotationDto fetched = collaborationService.getAnnotation(created.getId());
        assertNotNull(fetched);
        assertEquals(created.getId(), fetched.getId());
        assertEquals("Test Domain", fetched.getLabel());
        assertNotNull(fetched.getCreatedAt(), "CreatedAt timestamp should be set");
        assertNotNull(fetched.getVersion(), "Version should be set");

        AnnotationDto update = AnnotationDto.builder()
                .label("Updated Domain")
                .version(fetched.getVersion())
                .build();
        AnnotationDto updated = collaborationService.updateAnnotation(fetched.getId(), update);

        AnnotationDto afterUpdate = collaborationService.getAnnotation(created.getId());
        assertNotNull(afterUpdate.getUpdatedAt(), "UpdatedAt timestamp should be set after update");
        assertEquals(Integer.valueOf(fetched.getVersion() + 1), afterUpdate.getVersion(),
                "Version should increment after update");
    }

    @Test
    @Order(17)
    @DisplayName("Update without version field succeeds (backward compatibility)")
    void updateWithoutVersionSucceeds() {
        Long compatStructId = 400L;
        AnnotationDto dto = AnnotationDto.builder()
                .structureId(compatStructId)
                .type("pocket")
                .label("Binding Pocket")
                .positionX(5.0).positionY(5.0).positionZ(5.0)
                .createdBy(1L)
                .build();

        AnnotationDto created = collaborationService.addAnnotation(compatStructId, dto);

        AnnotationDto updateWithoutVersion = AnnotationDto.builder()
                .label("Updated Pocket - no version")
                .build();

        AnnotationDto result = collaborationService.updateAnnotation(created.getId(), updateWithoutVersion);
        assertEquals("Updated Pocket - no version", result.getLabel(),
                "Update without version should succeed for backward compatibility");
        assertNotNull(result.getVersion(), "Version should still be present and incremented");
    }
}
