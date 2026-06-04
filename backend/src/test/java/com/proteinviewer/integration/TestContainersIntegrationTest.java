package com.proteinviewer.integration;

import com.proteinviewer.dto.*;
import com.proteinviewer.model.Comment;
import com.proteinviewer.service.CollaborationService;
import com.proteinviewer.service.StructureService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisabledIfEnvironmentVariable(named = "SKIP_TESTCONTAINERS", matches = "true")
class TestContainersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("protein_viewer_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> minio = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("minio.endpoint", () -> "http://localhost:" + minio.getMappedPort(9000));
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
    }

    @Autowired
    private StructureService structureService;

    @Autowired
    private CollaborationService collaborationService;

    private static final String PDB_CONTENT =
            "HEADER    TEST STRUCTURE                            01-JAN-24   1TCN\n" +
            "TITLE     TESTCONTAINERS INTEGRATION TEST\n" +
            "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
            "ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n" +
            "ATOM      3  C   ALA A   1       2.500   2.200   1.500  1.00 19.00           C\n" +
            "ATOM      4  O   ALA A   1       3.200   2.900   0.800  1.00 22.00           O\n" +
            "ATOM      5  CB  ALA A   1       2.200  -0.300   1.500  1.00 17.00           C\n" +
            "ATOM      6  N   GLY A   2       2.300   2.500   2.800  1.00 15.00           N\n" +
            "ATOM      7  CA  GLY A   2       2.800   3.700   3.300  1.00 16.00           C\n" +
            "ATOM      8  C   GLY A   2       3.800   4.200   2.500  1.00 14.00           C\n" +
            "ATOM      9  O   GLY A   2       4.500   5.100   2.800  1.00 21.00           O\n" +
            "ATOM     10  N   ASP A   3       3.800   3.600   1.300  1.00 13.00           N\n" +
            "ATOM     11  CA  ASP A   3       4.600   4.000   0.200  1.00 18.00           C\n" +
            "ATOM     12  OD1 ASP A   3       4.800  -0.300  -0.400  1.00 25.00           O\n" +
            "ATOM     13  OD2 ASP A   3       4.200  -1.700  -2.000  1.00 27.00           O\n" +
            "ATOM     14  N   LYS A   4       7.800   1.800  -3.300  1.00 15.00           N\n" +
            "ATOM     15  CA  LYS A   4       8.900   2.400  -4.000  1.00 14.00           C\n" +
            "ATOM     16  NZ  LYS A   4      11.200   6.300  -4.200  1.00 24.00           N\n" +
            "HETATM   17  FE  HEM A 101       5.000   5.000   5.000  1.00 30.00          FE\n" +
            "CONECT   17  11  15\n" +
            "END\n";

    private static Long structureId1;
    private static Long structureId2;

    @Test
    @Order(1)
    @DisplayName("PostgreSQL + MinIO: Full upload → parse → store → retrieve pipeline")
    void fullPipelineWithTestContainers() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tc-test.pdb", "chemical/x-pdb",
                PDB_CONTENT.getBytes(StandardCharsets.UTF_8)
        );

        StructureUploadResponse response = structureService.uploadStructure(file, "tc-structure", 1L);
        structureId1 = response.getId();

        assertNotNull(response.getId());
        assertEquals("1TCN", response.getPdbId());
        assertEquals(17, response.getAtomCount());
        assertTrue(response.getResidueCount() > 0);
        assertFalse(response.getBondCount() == 0, "Should have CONECT bonds");

        PdbDataDto data = structureService.getStructureData(structureId1);
        assertNotNull(data);
        assertEquals(17, data.getAtoms().size());

        long hetatmCount = data.getAtoms().stream().filter(AtomInfoDto::isHetatm).count();
        assertEquals(1, hetatmCount, "Should have 1 HETATM (FE)");

        DistanceResultDto dist = structureService.calculateDistance(structureId1, 1, 2);
        assertTrue(dist.getDistance() > 0);

        AngleResultDto angle = structureService.calculateAngle(structureId1, 1, 2, 3);
        assertTrue(angle.getAngle() > 0 && angle.getAngle() < 180);
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL + MinIO: Multi-structure alignment pipeline")
    void alignmentPipelineWithTestContainers() {
        Assumptions.assumeTrue(structureId1 != null);

        String rotatedPdb = PDB_CONTENT
                .replace("ATOM      2  CA  ALA A   1       2.000   1.000   1.000",
                         "ATOM      2  CA  ALA A   1       2.000   4.000   1.000");

        MockMultipartFile file2 = new MockMultipartFile(
                "file", "tc-test2.pdb", "chemical/x-pdb",
                rotatedPdb.getBytes(StandardCharsets.UTF_8)
        );

        StructureUploadResponse response2 = structureService.uploadStructure(file2, "tc-structure-2", 1L);
        structureId2 = response2.getId();

        AlignmentResultDto alignment = structureService.alignStructures(structureId1, structureId2);

        assertNotNull(alignment);
        assertTrue(alignment.getRmsd() >= 0);
        assertTrue(alignment.getAlignedAtomCount() > 0);
        assertFalse(alignment.getPerResidueRmsd().isEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("PostgreSQL + MinIO: Collaboration pipeline (annotate → share → comment)")
    void collaborationPipelineWithTestContainers() {
        Assumptions.assumeTrue(structureId1 != null);

        AnnotationDto annotation = collaborationService.createAnnotation(AnnotationDto.builder()
                .structureId(structureId1)
                .type("active")
                .label("Active Site")
                .description("Catalytic residue")
                .positionX(4.6).positionY(4.0).positionZ(0.2)
                .color("#FFD700")
                .visible(true)
                .createdBy(1L)
                .build());

        assertNotNull(annotation.getId());

        List<AnnotationDto> annotations = collaborationService.getAnnotations(structureId1);
        assertTrue(annotations.stream().anyMatch(a -> "Active Site".equals(a.getLabel())));

        SnapshotDto snapshot = collaborationService.createSnapshot(SnapshotDto.builder()
                .structureId(structureId1)
                .cameraPositionX(40.0).cameraPositionY(30.0).cameraPositionZ(50.0)
                .cameraTargetX(5.0).cameraTargetY(5.0).cameraTargetZ(5.0)
                .cameraUpX(0.0).cameraUpY(1.0).cameraUpZ(0.0)
                .renderMode("ball-stick")
                .colorScheme("element")
                .build());

        assertNotNull(snapshot.getShortId());

        SnapshotDto loaded = collaborationService.getSnapshot(snapshot.getShortId());
        assertEquals(structureId1, loaded.getStructureId());
        assertEquals("ball-stick", loaded.getRenderMode());

        Comment comment = collaborationService.addComment(
                structureId1, "ASP-LYS salt bridge critical for function", 4.6, 4.0, 0.2, 1L);

        assertNotNull(comment.getId());

        Comment reply = collaborationService.addComment(
                structureId1, "Confirmed by mutagenesis study", 4.6, 4.0, 0.2, 2L);

        List<Comment> comments = collaborationService.getComments(structureId1);
        assertEquals(2, comments.size());
    }

    @Test
    @Order(4)
    @DisplayName("PostgreSQL + MinIO: Batch analysis with multiple structures")
    void batchAnalysisWithTestContainers() {
        Assumptions.assumeTrue(structureId1 != null && structureId2 != null);

        BatchAnalysisResultDto result = structureService.batchAnalysis(
                List.of(structureId1, structureId2)
        );

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(2, result.getRmsdMatrix().length);

        double rmsd01 = result.getRmsdMatrix()[0][1];
        double rmsd10 = result.getRmsdMatrix()[1][0];
        assertEquals(rmsd01, rmsd10, 0.001, "RMSD matrix should be symmetric");
        assertEquals(0.0, result.getRmsdMatrix()[0][0], 0.001);

        assertNotNull(result.getBfactorStats());
        assertTrue(result.getBfactorStats().size() >= 1);
    }
}
