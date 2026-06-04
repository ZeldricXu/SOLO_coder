package com.proteinviewer.unit;

import com.proteinviewer.dto.*;
import com.proteinviewer.model.AtomRecord;
import com.proteinviewer.model.ParsedPdb;
import com.proteinviewer.service.MolecularAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MolecularAnalysisTest {

    private MolecularAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new MolecularAnalysisService();
    }

    private AtomRecord makeAtom(int serial, String atomName, String resName, String chainId,
                                int resSeq, double x, double y, double z, String element,
                                double bfactor, boolean isHetatm) {
        return AtomRecord.builder()
                .serialNumber(serial).atomName(atomName).residueName(resName)
                .chainId(chainId).residueSeqNumber(resSeq)
                .x(x).y(y).z(z).element(element)
                .tempFactor(bfactor).isHetatm(isHetatm)
                .build();
    }

    private ParsedPdb makePdb(List<AtomRecord> atoms) {
        return ParsedPdb.builder()
                .atoms(atoms)
                .bonds(new ArrayList<>())
                .validation(new com.proteinviewer.model.ValidationResult(true, new ArrayList<>()))
                .totalAtoms(atoms.size())
                .totalResidues(1)
                .chainIds(List.of("A"))
                .build();
    }

    @Nested
    @DisplayName("Distance Calculation")
    class DistanceCalculation {

        @Test
        @DisplayName("Distance between two atoms matches manual calculation")
        void distanceMatchesManualCalc() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 0.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 3.0, 4.0, 0.0, "C", 18.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            DistanceResultDto result = service.calculateDistance(pdb, 1, 2);

            assertEquals(5.0, result.getDistance(), 0.001, "3-4-5 triangle distance should be 5.0");
            assertEquals("angstrom", result.getUnit());
        }

        @Test
        @DisplayName("Distance between identical atoms is zero")
        void distanceZeroForSamePosition() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 5.0, 5.0, 5.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 5.0, 5.0, 5.0, "C", 18.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            DistanceResultDto result = service.calculateDistance(pdb, 1, 2);

            assertEquals(0.0, result.getDistance(), 0.001);
        }

        @Test
        @DisplayName("Distance is symmetric (d(A,B) == d(B,A))")
        void distanceSymmetric() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 1.0, 2.0, 3.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 4.0, 6.0, 8.0, "C", 18.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            DistanceResultDto r1 = service.calculateDistance(pdb, 1, 2);
            DistanceResultDto r2 = service.calculateDistance(pdb, 2, 1);

            assertEquals(r1.getDistance(), r2.getDistance(), 0.0001);
        }

        @Test
        @DisplayName("Distance calculation with negative coordinates")
        void distanceWithNegativeCoordinates() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, -3.0, -4.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", 18.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            DistanceResultDto result = service.calculateDistance(pdb, 1, 2);

            assertEquals(5.0, result.getDistance(), 0.001);
        }

        @Test
        @DisplayName("Distance throws for nonexistent atom serial")
        void distanceThrowsForNonexistentAtom() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 0.0, 0.0, 0.0, "N", 20.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            assertThrows(IllegalArgumentException.class, () -> service.calculateDistance(pdb, 1, 999));
        }

        @Test
        @DisplayName("3D Euclidean distance with all axes")
        void threeDimensionalDistance() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 1.0, 2.0, 3.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 4.0, 6.0, 3.0, "C", 18.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            DistanceResultDto result = service.calculateDistance(pdb, 1, 2);

            assertEquals(5.0, result.getDistance(), 0.001, "sqrt(9+16+0) = 5.0");
        }
    }

    @Nested
    @DisplayName("Angle Calculation")
    class AngleCalculation {

        @Test
        @DisplayName("180° angle for collinear atoms (straight line)")
        void oneEightyDegreeAngle() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 0.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 5.0, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "C", "ALA", "A", 1, 10.0, 0.0, 0.0, "C", 19.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            AngleResultDto result = service.calculateAngle(pdb, 1, 2, 3);

            assertEquals(180.0, result.getAngle(), 0.01);
        }

        @Test
        @DisplayName("90° right angle")
        void ninetyDegreeAngle() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 1.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "C", "ALA", "A", 1, 0.0, 1.0, 0.0, "C", 19.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            AngleResultDto result = service.calculateAngle(pdb, 1, 2, 3);

            assertEquals(90.0, result.getAngle(), 0.01);
        }

        @Test
        @DisplayName("109.5° tetrahedral angle (sp3 hybridization)")
        void tetrahedralAngle() {
            double cosAngle = -1.0 / 3.0;
            double sinAngle = Math.sqrt(8.0 / 9.0);
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "H1", "MET", "A", 1, 1.0, 0.0, 0.0, "H", 20.0, false),
                    makeAtom(2, "C", "MET", "A", 1, 0.0, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "H2", "MET", "A", 1, cosAngle, sinAngle, 0.0, "H", 19.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            AngleResultDto result = service.calculateAngle(pdb, 1, 2, 3);

            assertEquals(109.47, result.getAngle(), 0.1, "Tetrahedral angle should be ~109.5°");
        }

        @Test
        @DisplayName("0° angle for coincident direction vectors")
        void zeroAngle() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 1.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "C", "ALA", "A", 1, 2.0, 0.0, 0.0, "C", 19.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            AngleResultDto result = service.calculateAngle(pdb, 1, 2, 3);

            assertEquals(0.0, result.getAngle(), 0.01);
        }

        @Test
        @DisplayName("60° equilateral triangle angle")
        void sixtyDegreeAngle() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 1.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "C", "ALA", "A", 1, 0.5, Math.sqrt(3) / 2, 0.0, "C", 19.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            AngleResultDto result = service.calculateAngle(pdb, 1, 2, 3);

            assertEquals(60.0, result.getAngle(), 0.1);
        }
    }

    @Nested
    @DisplayName("Interaction Analysis")
    class InteractionAnalysis {

        @Test
        @DisplayName("Hydrogen bond detected between donor N-H and acceptor O within 3.5 Å")
        void hydrogenBondDetection() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 0.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 1.5, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "O", "GLY", "A", 2, 3.0, 0.0, 0.0, "O", 19.0, false),
                    makeAtom(4, "CA", "GLY", "A", 2, 4.0, 0.0, 0.0, "C", 17.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            InteractionResultDto result = service.analyzeInteractions(pdb, "A", 1, 5.0);

            boolean hasHbond = result.getInteractions().stream()
                    .anyMatch(i -> i.getType().equals("hydrogen_bond"));
            assertTrue(hasHbond, "Should detect hydrogen bond between ALA:1 N and GLY:2 O");
        }

        @Test
        @DisplayName("Hydrophobic contact detected between two hydrophobic residues within 5 Å")
        void hydrophobicContactDetection() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CA", "LEU", "A", 1, 0.0, 0.0, 0.0, "C", 20.0, false),
                    makeAtom(2, "CB", "LEU", "A", 1, 1.5, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "CA", "VAL", "A", 2, 4.0, 0.0, 0.0, "C", 19.0, false),
                    makeAtom(4, "CB", "VAL", "A", 2, 5.0, 0.0, 0.0, "C", 17.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            InteractionResultDto result = service.analyzeInteractions(pdb, "A", 1, 5.0);

            boolean hasHydrophobic = result.getInteractions().stream()
                    .anyMatch(i -> i.getType().equals("hydrophobic"));
            assertTrue(hasHydrophobic, "Should detect hydrophobic contact between LEU and VAL");
        }

        @Test
        @DisplayName("Salt bridge detected between LYS (positive) and ASP (negative) within 4 Å")
        void saltBridgeDetection() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "NZ", "LYS", "A", 1, 0.0, 0.0, 0.0, "N", 20.0, false),
                    makeAtom(2, "CE", "LYS", "A", 1, 1.5, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "OD1", "ASP", "A", 2, 3.5, 0.0, 0.0, "O", 19.0, false),
                    makeAtom(4, "CG", "ASP", "A", 2, 4.5, 0.0, 0.0, "C", 17.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            InteractionResultDto result = service.analyzeInteractions(pdb, "A", 1, 5.0);

            boolean hasSaltBridge = result.getInteractions().stream()
                    .anyMatch(i -> i.getType().equals("salt_bridge"));
            assertTrue(hasSaltBridge, "Should detect salt bridge between LYS and ASP");
        }

        @Test
        @DisplayName("Pi-pi stacking detected between two aromatic residues within 7 Å")
        void piPiStackingDetection() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CG", "PHE", "A", 1, 0.0, 0.0, 0.0, "C", 20.0, false),
                    makeAtom(2, "CD1", "PHE", "A", 1, 1.0, 0.0, 0.0, "C", 18.0, false),
                    makeAtom(3, "CG", "TRP", "A", 2, 5.0, 0.0, 0.0, "C", 19.0, false),
                    makeAtom(4, "CD1", "TRP", "A", 2, 6.0, 0.0, 0.0, "C", 17.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            InteractionResultDto result = service.analyzeInteractions(pdb, "A", 1, 7.0);

            boolean hasPiPi = result.getInteractions().stream()
                    .anyMatch(i -> i.getType().equals("pi_pi_stacking"));
            assertTrue(hasPiPi, "Should detect pi-pi stacking between PHE and TRP");
        }

        @Test
        @DisplayName("No interactions detected beyond cutoff distance")
        void noInteractionsBeyondCutoff() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", 20.0, false),
                    makeAtom(2, "CA", "GLY", "A", 2, 100.0, 100.0, 100.0, "C", 18.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            InteractionResultDto result = service.analyzeInteractions(pdb, "A", 1, 5.0);

            assertEquals(0, result.getInteractions().size(), "No interactions should be found beyond cutoff");
        }

        @Test
        @DisplayName("Interaction analysis throws for nonexistent residue")
        void interactionThrowsForNonexistentResidue() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", 20.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            assertThrows(IllegalArgumentException.class,
                    () -> service.analyzeInteractions(pdb, "A", 999, 5.0));
        }
    }

    @Nested
    @DisplayName("Disulfide Bond Detection")
    class DisulfideBondDetection {

        @Test
        @DisplayName("Disulfide bond detected between two CYS SG atoms within 2.5 Å")
        void disulfideBondDetected() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "SG", "CYS", "A", 5, 0.0, 0.0, 0.0, "S", 20.0, false),
                    makeAtom(2, "SG", "CYS", "A", 55, 2.0, 0.0, 0.0, "S", 20.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            List<BatchAnalysisResultDto.DisulfideBond> bonds = service.detectDisulfideBonds(pdb, 1L);

            assertEquals(1, bonds.size());
            assertEquals(5, bonds.get(0).getResSeq1());
            assertEquals(55, bonds.get(0).getResSeq2());
            assertTrue(bonds.get(0).getDistance() <= 2.5);
        }

        @Test
        @DisplayName("No disulfide bond when SG atoms are far apart")
        void noDisulfideWhenFarApart() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "SG", "CYS", "A", 5, 0.0, 0.0, 0.0, "S", 20.0, false),
                    makeAtom(2, "SG", "CYS", "A", 55, 10.0, 0.0, 0.0, "S", 20.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            List<BatchAnalysisResultDto.DisulfideBond> bonds = service.detectDisulfideBonds(pdb, 1L);

            assertEquals(0, bonds.size());
        }
    }

    @Nested
    @DisplayName("B-Factor Analysis")
    class BFactorAnalysis {

        @Test
        @DisplayName("B-factor statistics computed correctly (mean, stdDev, min, max, median)")
        void bfactorStatsComputed() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 0, 0, 0, "N", 10.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 1, 0, 0, "C", 20.0, false),
                    makeAtom(3, "C", "ALA", "A", 1, 2, 0, 0, "C", 30.0, false),
                    makeAtom(4, "O", "ALA", "A", 1, 3, 0, 0, "O", 40.0, false),
                    makeAtom(5, "CB", "ALA", "A", 1, 4, 0, 0, "C", 50.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            BatchAnalysisResultDto.BFactorStats stats = service.analyzeBFactor(pdb, 1L);

            assertEquals(30.0, stats.getMean(), 0.01);
            assertEquals(10.0, stats.getMin(), 0.01);
            assertEquals(50.0, stats.getMax(), 0.01);
            assertEquals(30.0, stats.getMedian(), 0.01);
        }

        @Test
        @DisplayName("B-factor stats for empty atom list")
        void bfactorEmptyList() {
            ParsedPdb pdb = makePdb(new ArrayList<>());

            BatchAnalysisResultDto.BFactorStats stats = service.analyzeBFactor(pdb, 1L);

            assertNotNull(stats);
        }

        @Test
        @DisplayName("B-factor standard deviation matches manual calculation")
        void bfactorStdDev() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "N", "ALA", "A", 1, 0, 0, 0, "N", 10.0, false),
                    makeAtom(2, "CA", "ALA", "A", 1, 1, 0, 0, "C", 20.0, false),
                    makeAtom(3, "C", "ALA", "A", 1, 2, 0, 0, "C", 30.0, false)
            );
            ParsedPdb pdb = makePdb(atoms);

            BatchAnalysisResultDto.BFactorStats stats = service.analyzeBFactor(pdb, 1L);

            double expectedStdDev = Math.sqrt(((10 - 20) * (10 - 20) + (20 - 20) * (20 - 20) + (30 - 20) * (30 - 20)) / 3.0);
            assertEquals(expectedStdDev, stats.getStdDev(), 0.01);
        }
    }
}
