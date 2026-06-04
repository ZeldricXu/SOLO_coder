package com.proteinviewer.integration;

import com.proteinviewer.dto.*;
import com.proteinviewer.model.Comment;
import com.proteinviewer.service.CollaborationService;
import com.proteinviewer.service.StructureService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StructureWorkflowIntegrationTest {

    @Autowired
    private StructureService structureService;

    @Autowired
    private CollaborationService collaborationService;

    private static Long structureId1;
    private static Long structureId2;

    private static final String SAMPLE_PDB =
            "HEADER    OXYGEN TRANSPORT                        01-JAN-24   1TST\n" +
            "TITLE     INTEGRATION TEST PROTEIN\n" +
            "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
            "ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n" +
            "ATOM      3  C   ALA A   1       2.500   2.200   1.500  1.00 19.00           C\n" +
            "ATOM      4  O   ALA A   1       3.200   2.900   0.800  1.00 22.00           O\n" +
            "ATOM      5  CB  ALA A   1       2.200  -0.300   1.500  1.00 17.00           C\n" +
            "ATOM      6  N   GLY A   2       2.300   2.500   2.800  1.00 15.00           N\n" +
            "ATOM      7  CA  GLY A   2       2.800   3.700   3.300  1.00 16.00           C\n" +
            "ATOM      8  C   GLY A   2       3.800   4.200   2.500  1.00 14.00           C\n" +
            "ATOM      9  O   GLY A   2       4.500   5.100   2.800  1.00 21.00           O\n" +
            "ATOM     10  N   LEU A   3       3.800   3.600   1.300  1.00 13.00           N\n" +
            "ATOM     11  CA  LEU A   3       4.600   4.000   0.200  1.00 12.00           C\n" +
            "ATOM     12  C   LEU A   3       5.800   3.000  -0.100  1.00 15.00           C\n" +
            "ATOM     13  O   LEU A   3       6.800   3.200   0.500  1.00 19.00           O\n" +
            "ATOM     14  CB  LEU A   3       4.800   5.400  -0.400  1.00 11.00           C\n" +
            "ATOM     15  CG  LEU A   3       4.000   6.300  -1.300  1.00 10.00           C\n" +
            "ATOM     16  CD1 LEU A   3       4.800   6.400  -2.600  1.00 14.00           C\n" +
            "ATOM     17  CD2 LEU A   3       2.700   5.800  -1.500  1.00 13.00           C\n" +
            "ATOM     18  N   ASP A   4       5.600   2.000  -1.000  1.00 16.00           N\n" +
            "ATOM     19  CA  ASP A   4       6.600   1.000  -1.400  1.00 18.00           C\n" +
            "ATOM     20  C   ASP A   4       7.800   1.600  -2.000  1.00 17.00           C\n" +
            "ATOM     21  O   ASP A   4       8.800   2.000  -1.300  1.00 23.00           O\n" +
            "ATOM     22  CB  ASP A   4       6.200  -0.200  -2.200  1.00 20.00           C\n" +
            "ATOM     23  CG  ASP A   4       5.000  -0.800  -1.500  1.00 22.00           C\n" +
            "ATOM     24  OD1 ASP A   4       4.800  -0.300  -0.400  1.00 25.00           O\n" +
            "ATOM     25  OD2 ASP A   4       4.200  -1.700  -2.000  1.00 27.00           O\n" +
            "ATOM     26  N   LYS A   5       7.800   1.800  -3.300  1.00 15.00           N\n" +
            "ATOM     27  CA  LYS A   5       8.900   2.400  -4.000  1.00 14.00           C\n" +
            "ATOM     28  C   LYS A   5       9.500   1.400  -4.900  1.00 13.00           C\n" +
            "ATOM     29  O   LYS A   5      10.500   1.600  -5.400  1.00 18.00           O\n" +
            "ATOM     30  CB  LYS A   5       8.600   3.700  -4.700  1.00 16.00           C\n" +
            "ATOM     31  CG  LYS A   5       9.600   4.700  -4.500  1.00 17.00           C\n" +
            "ATOM     32  CD  LYS A   5       9.200   5.800  -5.300  1.00 19.00           C\n" +
            "ATOM     33  CE  LYS A   5      10.200   6.700  -5.100  1.00 21.00           C\n" +
            "ATOM     34  NZ  LYS A   5      11.200   6.300  -4.200  1.00 24.00           N\n" +
            "HETATM   35  FE  HEM A 101       5.000   5.000   5.000  1.00 30.00          FE\n" +
            "HETATM   36  O   HOH A 201      12.000  12.000  12.000  1.00 40.00           O\n" +
            "CONECT   35  27  19\n" +
            "END\n";

    @Test
    @Order(1)
    @DisplayName("Full workflow: Upload PDB → Parse → Validate → Store → Retrieve JSON data")
    void fullUploadParseRetrieveWorkflow() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdb", "chemical/x-pdb",
                SAMPLE_PDB.getBytes(StandardCharsets.UTF_8)
        );

        StructureUploadResponse response = structureService.uploadStructure(file, "test-structure", 1L);
        structureId1 = response.getId();

        assertNotNull(response.getId());
        assertEquals("test-structure", response.getName());
        assertEquals("1TST", response.getPdbId());
        assertTrue(response.getAtomCount() > 0);
        assertTrue(response.getResidueCount() > 0);

        PdbDataDto data = structureService.getStructureData(structureId1);

        assertNotNull(data);
        assertEquals(structureId1, data.getStructureId());
        assertEquals("1TST", data.getPdbId());
        assertTrue(data.getTitle().contains("INTEGRATION TEST"));
        assertFalse(data.getAtoms().isEmpty());
        assertEquals(36, data.getTotalAtoms());

        boolean hasHetatm = data.getAtoms().stream().anyMatch(AtomInfoDto::isHetatm);
        assertTrue(hasHetatm, "Should contain HETATM records (FE and HOH)");

        long feCount = data.getAtoms().stream()
                .filter(a -> a.getElement().equals("FE"))
                .count();
        assertEquals(1, feCount, "Should have exactly one FE atom");

        assertFalse(data.getBonds().isEmpty(), "Should have CONECT bonds");
        assertFalse(data.getChainIds().isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("Distance calculation through service layer matches expected values")
    void distanceCalculationThroughService() {
        assumeStructure1Exists();

        DistanceResultDto result = structureService.calculateDistance(structureId1, 1, 2);

        assertNotNull(result);
        assertTrue(result.getDistance() > 0);
        assertEquals("angstrom", result.getUnit());
    }

    @Test
    @Order(3)
    @DisplayName("Angle calculation through service layer returns valid angle")
    void angleCalculationThroughService() {
        assumeStructure1Exists();

        AngleResultDto result = structureService.calculateAngle(structureId1, 1, 2, 3);

        assertNotNull(result);
        assertTrue(result.getAngle() > 0 && result.getAngle() < 180);
        assertEquals("degrees", result.getUnit());
    }

    @Test
    @Order(4)
    @DisplayName("Interaction analysis through service layer detects expected interactions")
    void interactionAnalysisThroughService() {
        assumeStructure1Exists();

        InteractionResultDto result = structureService.analyzeInteractions(structureId1, "A", 4, 5.0);

        assertNotNull(result);
        assertEquals("ASP", result.getCenterResidue());
        assertFalse(result.getInteractions().isEmpty(),
                "ASP should have interactions with nearby residues like LYS (salt bridge)");
    }

    @Test
    @Order(5)
    @DisplayName("Multi-structure alignment: Upload second PDB → Align → Get RMSD")
    void multiStructureAlignmentWorkflow() {
        String modifiedPdb = SAMPLE_PDB
                .replace("ATOM     11  CA  LEU A   3       4.600   4.000   0.200",
                         "ATOM     11  CA  LEU A   3       4.600   7.000   0.200")
                .replace("ATOM     12  C   LEU A   3       5.800   3.000  -0.100",
                         "ATOM     12  C   LEU A   3       5.800   6.000  -0.100");

        MockMultipartFile file2 = new MockMultipartFile(
                "file", "test2.pdb", "chemical/x-pdb",
                modifiedPdb.getBytes(StandardCharsets.UTF_8)
        );

        StructureUploadResponse response2 = structureService.uploadStructure(file2, "test-structure-2", 1L);
        structureId2 = response2.getId();

        AlignmentResultDto alignment = structureService.alignStructures(structureId1, structureId2);

        assertNotNull(alignment);
        assertTrue(alignment.getRmsd() > 0, "Different structures should have RMSD > 0");
        assertTrue(alignment.getPerResidueRmsd().size() > 0);
        assertEquals(alignment.getAlignedAtomCount(), alignment.getPerResidueRmsd().size());
    }

    @Test
    @Order(6)
    @DisplayName("Batch analysis: Upload multiple structures → RMSD matrix + disulfide + B-factor")
    void batchAnalysisWorkflow() {
        assumeStructure1Exists();

        MockMultipartFile file3 = new MockMultipartFile(
                "file", "test3.pdb", "chemical/x-pdb",
                SAMPLE_PDB.getBytes(StandardCharsets.UTF_8)
        );
        StructureUploadResponse response3 = structureService.uploadStructure(file3, "test-structure-3", 1L);
        Long structureId3 = response3.getId();

        BatchAnalysisResultDto result = structureService.batchAnalysis(
                List.of(structureId1, structureId3)
        );

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
        assertEquals(2, result.getRmsdMatrix().length);

        double rmsd01 = result.getRmsdMatrix()[0][1];
        double rmsd10 = result.getRmsdMatrix()[1][0];
        assertEquals(rmsd01, rmsd10, 0.001, "RMSD matrix should be symmetric");
        assertEquals(0.0, result.getRmsdMatrix()[0][0], 0.001, "Self-alignment RMSD should be 0");

        assertNotNull(result.getBfactorStats());
        assertNotNull(result.getDisulfideBonds());
        assertNotNull(result.getGlycosylationSites());
    }

    @Test
    @Order(7)
    @DisplayName("List structures returns uploaded entries")
    void listStructures() {
        assumeStructure1Exists();

        var structures = structureService.listStructures();

        assertFalse(structures.isEmpty());
        assertTrue(structures.stream().anyMatch(s -> s.getId().equals(structureId1)));
    }

    private void assumeStructure1Exists() {
        Assumptions.assumeTrue(structureId1 != null, "Structure 1 must be uploaded first");
    }
}
