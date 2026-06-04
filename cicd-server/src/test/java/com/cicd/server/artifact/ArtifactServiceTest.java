package com.cicd.server.artifact;

import com.cicd.common.enums.ArtifactType;
import com.cicd.server.entity.Artifact;
import com.cicd.server.entity.Project;
import com.cicd.server.repository.ArtifactRepository;
import com.cicd.server.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private NexusService nexusService;

    @Mock
    private HarborService harborService;

    @InjectMocks
    private ArtifactService artifactService;

    private Project testProject;
    private Artifact testArtifact;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(artifactService, "daysRetainAll", 30);
        ReflectionTestUtils.setField(artifactService, "daysRetainLatest", 90);
        ReflectionTestUtils.setField(artifactService, "latestCount", 3);
        ReflectionTestUtils.setField(artifactService, "cleanupBatchSize", 100);

        testProject = new Project();
        testProject.setId(1L);
        testProject.setName("test-project");

        testArtifact = new Artifact();
        testArtifact.setId(1L);
        testArtifact.setProject(testProject);
        testArtifact.setName("test-app.jar");
        testArtifact.setType(ArtifactType.JAR);
        testArtifact.setVersion("1.0.0");
        testArtifact.setRegistryUrl("http://nexus.example.com");
        testArtifact.setRepository("releases");
        testArtifact.setIsPinned(false);
        testArtifact.setCleanupStatus("NONE");
    }

    @Test
    void testGetArtifact() {
        when(artifactRepository.findById(1L)).thenReturn(Optional.of(testArtifact));

        Artifact result = artifactService.getArtifact(1L);

        assertNotNull(result);
        assertEquals("test-app.jar", result.getName());
    }

    @Test
    void testGetArtifactNotFound() {
        when(artifactRepository.findById(999L)).thenReturn(Optional.empty());

        Artifact result = artifactService.getArtifact(999L);

        assertNull(result);
    }

    @Test
    void testPinArtifact() {
        when(artifactRepository.findById(1L)).thenReturn(Optional.of(testArtifact));
        when(artifactRepository.save(any(Artifact.class))).thenReturn(testArtifact);

        artifactService.pinArtifact(1L, true);

        assertTrue(testArtifact.getIsPinned());
        assertNull(testArtifact.getExpiresAt());
        assertEquals("NONE", testArtifact.getCleanupStatus());
    }

    @Test
    void testUnpinArtifact() {
        testArtifact.setIsPinned(true);
        testArtifact.setCleanupStatus("PENDING");

        when(artifactRepository.findById(1L)).thenReturn(Optional.of(testArtifact));
        when(artifactRepository.save(any(Artifact.class))).thenReturn(testArtifact);

        artifactService.pinArtifact(1L, false);

        assertFalse(testArtifact.getIsPinned());
        assertNotNull(testArtifact.getExpiresAt());
    }

    @Test
    void testMarkExpiredArtifactsForCleanup() {
        Artifact expired = new Artifact();
        expired.setId(10L);
        expired.setName("old-app.jar");
        expired.setIsPinned(false);
        expired.setCleanupStatus("NONE");

        when(artifactRepository.findExpiredArtifacts(any(LocalDateTime.class)))
            .thenReturn(List.of(expired));
        when(artifactRepository.findArtifactsForCleanup(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of());
        when(projectRepository.findAll()).thenReturn(List.of(testProject));

        artifactService.markExpiredArtifactsForCleanup();

        verify(artifactRepository).updateCleanupStatusByIds(List.of(10L), "PENDING");
    }

    @Test
    void testExecutePendingCleanupsSuccess() throws Exception {
        Artifact pending = new Artifact();
        pending.setId(10L);
        pending.setName("old-app.jar");
        pending.setVersion("0.1.0");
        pending.setType(ArtifactType.JAR);
        pending.setRegistryUrl("http://nexus.example.com");
        pending.setRepository("releases");
        pending.setCleanupStatus("PENDING");
        pending.setIsPinned(false);

        when(artifactRepository.findPendingCleanup()).thenReturn(List.of(pending));

        artifactService.executePendingCleanups();

        verify(nexusService).deleteArtifact(
            pending.getRegistryUrl(), pending.getRepository(),
            pending.getName(), pending.getVersion());
        verify(artifactRepository).delete(pending);
    }

    @Test
    void testExecutePendingCleanupsFailureRollback() throws Exception {
        Artifact pending = new Artifact();
        pending.setId(10L);
        pending.setName("old-app.jar");
        pending.setVersion("0.1.0");
        pending.setType(ArtifactType.DOCKER_IMAGE);
        pending.setRegistryUrl("http://harbor.example.com");
        pending.setRepository("myproject");
        pending.setCleanupStatus("PENDING");
        pending.setIsPinned(false);

        when(artifactRepository.findPendingCleanup()).thenReturn(List.of(pending));
        doThrow(new RuntimeException("Harbor API error")).when(harborService)
            .deleteImage(anyString(), anyString(), anyString(), anyString());

        artifactService.executePendingCleanups();

        verify(artifactRepository).updateCleanupStatus(10L, "NONE");
    }

    @Test
    void testRollbackCleanupStatus() {
        artifactService.rollbackCleanupStatus(10L);

        verify(artifactRepository).updateCleanupStatus(10L, "NONE");
    }

    @Test
    void testResetStalePendingCleanups() {
        Artifact stale = new Artifact();
        stale.setId(5L);
        stale.setCleanupStatus("PENDING");

        when(artifactRepository.findByCleanupStatus("PENDING")).thenReturn(List.of(stale));

        artifactService.resetStalePendingCleanups();

        verify(artifactRepository).updateCleanupStatusByIds(List.of(5L), "NONE");
    }

    @Test
    void testMarkOldVersionsForCleanup() {
        when(artifactRepository.findExpiredArtifacts(any(LocalDateTime.class)))
            .thenReturn(List.of());
        when(projectRepository.findAll()).thenReturn(List.of(testProject));

        LocalDateTime now = LocalDateTime.now();
        Artifact v1 = new Artifact();
        v1.setId(1L);
        v1.setName("app");
        v1.setIsPinned(false);
        v1.setCleanupStatus("NONE");
        v1.setCreatedAt(now.minusDays(60));

        Artifact v2 = new Artifact();
        v2.setId(2L);
        v2.setName("app");
        v2.setIsPinned(false);
        v2.setCleanupStatus("NONE");
        v2.setCreatedAt(now.minusDays(50));

        Artifact v3 = new Artifact();
        v3.setId(3L);
        v3.setName("app");
        v3.setIsPinned(false);
        v3.setCleanupStatus("NONE");
        v3.setCreatedAt(now.minusDays(40));

        Artifact v4 = new Artifact();
        v4.setId(4L);
        v4.setName("app");
        v4.setIsPinned(false);
        v4.setCleanupStatus("NONE");
        v4.setCreatedAt(now.minusDays(35));

        when(artifactRepository.findArtifactsForCleanup(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of(v1, v2, v3, v4));

        artifactService.markExpiredArtifactsForCleanup();

        verify(artifactRepository).updateCleanupStatusByIds(List.of(1L), "PENDING");
    }

    @Test
    void testDeleteArtifact() {
        artifactService.deleteArtifact(1L);
        verify(artifactRepository).deleteById(1L);
    }

    @Test
    void testTraceArtifact() {
        when(artifactRepository.findByProjectIdAndNameAndVersion(1L, "test-app.jar", "1.0.0"))
            .thenReturn(Optional.of(testArtifact));

        Artifact result = artifactService.traceArtifact("1.0.0", "test-app.jar", 1L);

        assertNotNull(result);
        assertEquals("1.0.0", result.getVersion());
    }

    @Test
    void testTraceByCommit() {
        when(artifactRepository.findByGitCommitSha("abc123"))
            .thenReturn(Optional.of(testArtifact));

        Artifact result = artifactService.traceByCommit("abc123");

        assertNotNull(result);
    }
}
