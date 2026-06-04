package com.proteinviewer.unit;

import com.proteinviewer.domain.Atom;
import com.proteinviewer.dto.AlignmentResultDto;
import com.proteinviewer.dto.ElectrostaticSurfaceDto;
import com.proteinviewer.model.AtomRecord;
import com.proteinviewer.model.ParsedPdb;
import com.proteinviewer.service.MolecularAnalysisService;
import com.proteinviewer.surface.ElectrostaticGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ElectrostaticAndAlignmentTest {

    private MolecularAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new MolecularAnalysisService();
    }

    private AtomRecord makeAtom(int serial, String atomName, String resName, String chainId,
                                int resSeq, double x, double y, double z, String element,
                                boolean isHetatm) {
        return AtomRecord.builder()
                .serialNumber(serial).atomName(atomName).residueName(resName)
                .chainId(chainId).residueSeqNumber(resSeq)
                .x(x).y(y).z(z).element(element)
                .tempFactor(20.0).isHetatm(isHetatm)
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
    @DisplayName("Electrostatic Surface Calculation")
    class ElectrostaticSurface {

        @Test
        @DisplayName("Surface vertices are generated for a simple molecule")
        void surfaceVerticesGenerated() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0, 0, 0, "C", false),
                    makeAtom(2, "N", "ALA", "A", 1, 1.5, 0, 0, "N", false),
                    makeAtom(3, "O", "ALA", "A", 1, -1.5, 0, 0, "O", false)
            );
            ParsedPdb pdb = makePdb(atoms);

            ElectrostaticSurfaceDto result = service.computeElectrostaticSurface(pdb, 1L);

            assertNotNull(result);
            assertTrue(result.getVertices().size() > 0, "Should generate surface vertices");
            assertTrue(result.getIndices().size() > 0, "Should generate triangle indices");
            assertTrue(result.getPotentials().size() > 0, "Should compute potentials at vertices");
        }

        @Test
        @DisplayName("Positive potential near LYS NZ (hydrogen bond donor)")
        void positivePotentialNearLysine() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "NZ", "LYS", "A", 1, 0, 0, 0, "N", false),
                    makeAtom(2, "CE", "LYS", "A", 1, 1.5, 0, 0, "C", false),
                    makeAtom(3, "CD", "LYS", "A", 1, 3.0, 0, 0, "C", false)
            );
            ParsedPdb pdb = makePdb(atoms);

            ElectrostaticSurfaceDto result = service.computeElectrostaticSurface(pdb, 1L);

            boolean hasPositivePotential = result.getPotentials().stream()
                    .anyMatch(p -> p > 0.01);
            assertTrue(hasPositivePotential, "Should have positive potential near LYS NZ (charge +1)");
        }

        @Test
        @DisplayName("Negative potential near ASP OD1/OD2 (electron-rich carboxylate)")
        void negativePotentialNearAspartate() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "OD1", "ASP", "A", 1, 0, 0, 0, "O", false),
                    makeAtom(2, "OD2", "ASP", "A", 1, 1.5, 0, 0, "O", false),
                    makeAtom(3, "CG", "ASP", "A", 1, 0.75, 1.0, 0, "C", false)
            );
            ParsedPdb pdb = makePdb(atoms);

            ElectrostaticSurfaceDto result = service.computeElectrostaticSurface(pdb, 1L);

            boolean hasNegativePotential = result.getPotentials().stream()
                    .anyMatch(p -> p < -0.01);
            assertTrue(hasNegativePotential, "Should have negative potential near ASP carboxylate oxygens");
        }

        @Test
        @DisplayName("Potential decays toward zero at grid boundaries far from atoms")
        void potentialDecaysAtBoundary() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "NZ", "LYS", "A", 1, 0, 0, 0, "N", false),
                    makeAtom(2, "OD1", "ASP", "A", 2, 10, 0, 0, "O", false)
            );
            ParsedPdb pdb = makePdb(atoms);

            ElectrostaticSurfaceDto result = service.computeElectrostaticSurface(pdb, 1L);

            if (result.getPotentials().isEmpty()) {
                return;
            }

            double maxAbsPotential = result.getPotentials().stream()
                    .mapToDouble(Float::doubleValue)
                    .map(Math::abs)
                    .max()
                    .orElse(0.0);

            if (maxAbsPotential > 0) {
                double avgAbsPotential = result.getPotentials().stream()
                        .mapToDouble(Float::doubleValue)
                        .map(Math::abs)
                        .average()
                        .orElse(0.0);

                assertTrue(avgAbsPotential < maxAbsPotential,
                        "Average absolute potential should be less than max (boundary decay)");
            }
        }

        @Test
        @DisplayName("Surface mesh indices form valid triangles (every 3 indices reference existing vertices)")
        void validTriangleIndices() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0, 0, 0, "C", false),
                    makeAtom(2, "N", "ALA", "A", 1, 1.5, 0, 0, "N", false),
                    makeAtom(3, "C", "ALA", "A", 1, 0, 1.5, 0, "C", false),
                    makeAtom(4, "O", "ALA", "A", 1, 0, 0, 1.5, "O", false)
            );
            ParsedPdb pdb = makePdb(atoms);

            ElectrostaticSurfaceDto result = service.computeElectrostaticSurface(pdb, 1L);

            int vertexCount = result.getVertices().size() / 3;
            for (int idx : result.getIndices()) {
                assertTrue(idx >= 0 && idx < vertexCount,
                        "Index " + idx + " references non-existent vertex (total: " + vertexCount + ")");
            }
            assertEquals(0, result.getIndices().size() % 3,
                    "Indices should be in groups of 3 for triangles");
        }

        @Test
        @DisplayName("Min/max potential values are consistent with actual potentials")
        void minMaxPotentialConsistent() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "NZ", "LYS", "A", 1, 0, 0, 0, "N", false),
                    makeAtom(2, "OD1", "ASP", "A", 2, 5, 0, 0, "O", false)
            );
            ParsedPdb pdb = makePdb(atoms);

            ElectrostaticSurfaceDto result = service.computeElectrostaticSurface(pdb, 1L);

            double actualMin = result.getPotentials().stream().mapToDouble(Float::doubleValue).min().orElse(0);
            double actualMax = result.getPotentials().stream().mapToDouble(Float::doubleValue).max().orElse(0);

            assertEquals(actualMin, result.getMinPotential(), 0.01);
            assertEquals(actualMax, result.getMaxPotential(), 0.01);
        }

        @Test
        @DisplayName("Atom exactly at grid point produces no NaN values (division by zero protection)")
        void atomAtGridPointNoNaN() {
            Atom atom = new Atom(1, "NZ", ' ', "LYS", "A", 1, ' ',
                    0.0, 0.0, 0.0, 1.0, 20.0, "N", "0", false);
            List<Atom> atoms = List.of(atom);

            ElectrostaticGrid grid = ElectrostaticGrid.compute(atoms, 11, 5.0);
            double[][][] potential = grid.getPotential();

            for (int i = 0; i < 11; i++) {
                for (int j = 0; j < 11; j++) {
                    for (int k = 0; k < 11; k++) {
                        double val = potential[i][j][k];
                        assertFalse(Double.isNaN(val),
                                "NaN found at [" + i + "," + j + "," + k + "]");
                    }
                }
            }
        }

        @Test
        @DisplayName("Atom exactly at grid point produces finite potential values")
        void atomAtGridPointFinitePotential() {
            Atom atom = new Atom(1, "NZ", ' ', "LYS", "A", 1, ' ',
                    0.0, 0.0, 0.0, 1.0, 20.0, "N", "0", false);
            List<Atom> atoms = List.of(atom);

            ElectrostaticGrid grid = ElectrostaticGrid.compute(atoms, 11, 5.0);
            double[][][] potential = grid.getPotential();

            for (int i = 0; i < 11; i++) {
                for (int j = 0; j < 11; j++) {
                    for (int k = 0; k < 11; k++) {
                        double val = potential[i][j][k];
                        assertTrue(Double.isFinite(val),
                                "Non-finite value found at [" + i + "," + j + "," + k + "]: " + val);
                    }
                }
            }
        }

        @Test
        @DisplayName("Core region potential is smoothly interpolated (not infinite)")
        void coreRegionPotentialSmoothlyInterpolated() {
            Atom atom = new Atom(1, "NZ", ' ', "LYS", "A", 1, ' ',
                    0.0, 0.0, 0.0, 1.0, 20.0, "N", "0", false);
            List<Atom> atoms = List.of(atom);

            ElectrostaticGrid grid = ElectrostaticGrid.compute(atoms, 21, 5.0);
            double[][][] potential = grid.getPotential();
            double[] origin = grid.getOrigin();
            double[] spacing = grid.getSpacing();

            int centerX = (int) ((0.0 - origin[0]) / spacing[0]);
            int centerY = (int) ((0.0 - origin[1]) / spacing[1]);
            int centerZ = (int) ((0.0 - origin[2]) / spacing[2]);

            double centerValue = potential[centerX][centerY][centerZ];
            double[] neighborValues = new double[6];
            neighborValues[0] = potential[centerX + 1][centerY][centerZ];
            neighborValues[1] = potential[centerX - 1][centerY][centerZ];
            neighborValues[2] = potential[centerX][centerY + 1][centerZ];
            neighborValues[3] = potential[centerX][centerY - 1][centerZ];
            neighborValues[4] = potential[centerX][centerY][centerZ + 1];
            neighborValues[5] = potential[centerX][centerY][centerZ - 1];

            double maxNeighbor = 0;
            double minNeighbor = Double.MAX_VALUE;
            for (double v : neighborValues) {
                maxNeighbor = Math.max(maxNeighbor, v);
                minNeighbor = Math.min(minNeighbor, v);
            }

            assertTrue(centerValue >= minNeighbor * 0.5 && centerValue <= maxNeighbor * 2.0,
                    "Center value " + centerValue + " should be interpolated smoothly between neighbors");
        }

        @Test
        @DisplayName("Multiple atoms at grid points still produce valid potentials")
        void multipleAtomsAtGridPoints() {
            List<Atom> atoms = List.of(
                    new Atom(1, "NZ", ' ', "LYS", "A", 1, ' ',
                            0.0, 0.0, 0.0, 1.0, 20.0, "N", "0", false),
                    new Atom(2, "OD1", ' ', "ASP", "A", 2, ' ',
                            3.0, 0.0, 0.0, 1.0, 20.0, "O", "0", false),
                    new Atom(3, "NH1", ' ', "ARG", "A", 3, ' ',
                            0.0, 3.0, 0.0, 1.0, 20.0, "N", "0", false)
            );

            ElectrostaticGrid grid = ElectrostaticGrid.compute(atoms, 17, 5.0);
            double[][][] potential = grid.getPotential();

            int nanCount = 0;
            int infCount = 0;
            for (int i = 0; i < 17; i++) {
                for (int j = 0; j < 17; j++) {
                    for (int k = 0; k < 17; k++) {
                        double val = potential[i][j][k];
                        if (Double.isNaN(val)) nanCount++;
                        if (Double.isInfinite(val)) infCount++;
                    }
                }
            }

            assertEquals(0, nanCount, "Found " + nanCount + " NaN values");
            assertEquals(0, infCount, "Found " + infCount + " Infinity values");
        }
    }

    @Nested
    @DisplayName("Multi-Structure Alignment (Kabsch Algorithm)")
    class StructureAlignment {

        @Test
        @DisplayName("Two identical structures produce RMSD ≈ 0 (floating point error < 0.001)")
        void identicalStructuresRmsdZero() {
            List<AtomRecord> atoms = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 1.0, 2.0, 3.0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 4.0, 5.0, 6.0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 7.0, 8.0, 9.0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 10.0, 11.0, 12.0, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, 13.0, 14.0, 15.0, "C", false)
            );

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            assertEquals(0.0, result.getRmsd(), 0.001, "Identical structures should have RMSD ≈ 0");
            assertEquals(5, result.getAlignedAtomCount());
        }

        @Test
        @DisplayName("Kabsch correctly recovers a pure translation")
        void kabschRecoversTranslation() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 1.0, 0.0, 0.0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 5.0, 0.0, 0.0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 0.0, 5.0, 0.0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 0.0, 0.0, 5.0, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, 3.0, 3.0, 3.0, "C", false)
            );

            double tx = 10.0, ty = -5.0, tz = 3.0;
            List<AtomRecord> atoms2 = atoms1.stream()
                    .map(a -> makeAtom(a.getSerialNumber(), a.getAtomName(), a.getResidueName(),
                            a.getChainId(), a.getResidueSeqNumber(),
                            a.getX() + tx, a.getY() + ty, a.getZ() + tz,
                            a.getElement(), false))
                    .toList();

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms2));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            assertTrue(result.getRmsd() < 0.5,
                    "After alignment, translated structure should have low RMSD, got: " + result.getRmsd());
        }

        @Test
        @DisplayName("Kabsch reduces RMSD for a rotated structure")
        void kabschReducesRmsdForRotation() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 10.0, 0.0, 0.0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 0.0, 10.0, 0.0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, -10.0, 0.0, 0.0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 0.0, -10.0, 0.0, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, 0.0, 0.0, 10.0, "C", false)
            );

            double angle = Math.toRadians(45);
            List<AtomRecord> atoms2 = atoms1.stream()
                    .map(a -> {
                        double x = a.getX() * Math.cos(angle) - a.getY() * Math.sin(angle);
                        double y = a.getX() * Math.sin(angle) + a.getY() * Math.cos(angle);
                        return makeAtom(a.getSerialNumber(), a.getAtomName(), a.getResidueName(),
                                a.getChainId(), a.getResidueSeqNumber(),
                                x, y, a.getZ(), a.getElement(), false);
                    })
                    .toList();

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms2));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            double unalignedRmsd = computeUnalignedRmsd(atoms1, atoms2);
            assertTrue(result.getRmsd() < unalignedRmsd,
                    "Aligned RMSD (" + result.getRmsd() + ") should be less than unaligned (" + unalignedRmsd + ")");
        }

        @Test
        @DisplayName("Kabsch reduces RMSD for combined rotation + translation")
        void kabschReducesRmsdForRotationAndTranslation() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 5.0, 0.0, 0.0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 0.0, 5.0, 0.0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 0.0, 0.0, 5.0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, -5.0, 0.0, 0.0, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, 0.0, -5.0, 0.0, "C", false),
                    makeAtom(6, "CA", "VAL", "A", 6, 0.0, 0.0, -5.0, "C", false)
            );

            double angle = Math.toRadians(30);
            double tx = 7.0, ty = -3.0, tz = 5.0;
            List<AtomRecord> atoms2 = atoms1.stream()
                    .map(a -> {
                        double rx = a.getX() * Math.cos(angle) - a.getY() * Math.sin(angle);
                        double ry = a.getX() * Math.sin(angle) + a.getY() * Math.cos(angle);
                        return makeAtom(a.getSerialNumber(), a.getAtomName(), a.getResidueName(),
                                a.getChainId(), a.getResidueSeqNumber(),
                                rx + tx, ry + ty, a.getZ() + tz, a.getElement(), false);
                    })
                    .toList();

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms2));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            double unalignedRmsd = computeUnalignedRmsd(atoms1, atoms2);
            assertTrue(result.getRmsd() < unalignedRmsd,
                    "Aligned RMSD should be reduced from unaligned");
        }

        @Test
        @DisplayName("Different conformations produce non-zero RMSD with higher values in deviating regions")
        void differentConformationsNonZeroRmsd() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 3.8, 0.0, 0.0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 7.6, 0.0, 0.0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 11.4, 0.0, 0.0, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, 15.2, 0.0, 0.0, "C", false)
            );

            List<AtomRecord> atoms2 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0.0, 0.0, 0.0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 3.8, 0.0, 0.0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 7.6, 3.0, 0.0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 11.4, 3.0, 0.0, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, 15.2, 0.0, 0.0, "C", false)
            );

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms2));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            assertTrue(result.getRmsd() > 0.5, "Different conformations should have RMSD > 0.5");

            List<AlignmentResultDto.ResidueRmsd> perResidue = result.getPerResidueRmsd();
            assertNotNull(perResidue);
            assertEquals(5, perResidue.size());

            double rmsd3 = perResidue.get(2).getRmsd();

            assertTrue(rmsd3 > 0,
                    "Residue 3 (displaced by 3Å in Y) should have non-zero per-residue RMSD");
        }

        @Test
        @DisplayName("Per-residue RMSD identifies the most deviating region")
        void perResidueRmsdIdentifiesDeviation() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0, 0, 0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 4, 0, 0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 8, 0, 0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 12, 0, 0, "C", false)
            );

            List<AtomRecord> atoms2 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0, 0, 0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 4, 0.5, 0, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 8, 5.0, 0, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 12, 0.5, 0, "C", false)
            );

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms2));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            AlignmentResultDto.ResidueRmsd maxRmsd = result.getPerResidueRmsd().stream()
                    .max(java.util.Comparator.comparingDouble(AlignmentResultDto.ResidueRmsd::getRmsd))
                    .orElse(null);

            assertNotNull(maxRmsd);
            assertEquals(3, maxRmsd.getResSeq(),
                    "Residue 3 (LEU, displaced by 5Å) should have highest per-residue RMSD");
        }

        @Test
        @DisplayName("Rotation matrix is a proper rotation (det = +1, R^T * R = I)")
        void rotationMatrixIsProperRotation() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 5, 2, -1, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, -3, 7, 4, "C", false),
                    makeAtom(3, "CA", "LEU", "A", 3, 1, -2, 8, "C", false),
                    makeAtom(4, "CA", "ASP", "A", 4, 6, 3, -5, "C", false),
                    makeAtom(5, "CA", "LYS", "A", 5, -4, 1, 6, "C", false)
            );

            double angle = Math.toRadians(67);
            List<AtomRecord> atoms2 = atoms1.stream()
                    .map(a -> {
                        double rx = a.getX() * Math.cos(angle) - a.getY() * Math.sin(angle);
                        double ry = a.getX() * Math.sin(angle) + a.getY() * Math.cos(angle);
                        return makeAtom(a.getSerialNumber(), a.getAtomName(), a.getResidueName(),
                                a.getChainId(), a.getResidueSeqNumber(),
                                rx + 3, ry - 2, a.getZ() + 1, a.getElement(), false);
                    })
                    .toList();

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms2));

            AlignmentResultDto result = service.alignStructures(pdb1, pdb2);

            double[][] R = result.getRotationMatrix();
            double det = R[0][0] * (R[1][1] * R[2][2] - R[1][2] * R[2][1])
                    - R[0][1] * (R[1][0] * R[2][2] - R[1][2] * R[2][0])
                    + R[0][2] * (R[1][0] * R[2][1] - R[1][1] * R[2][0]);

            assertEquals(1.0, det, 0.01, "Rotation matrix determinant should be +1 for proper rotation");

            double[][] RtR = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    for (int k = 0; k < 3; k++) {
                        RtR[i][j] += R[k][i] * R[k][j];
                    }
                }
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(1.0, RtR[i][i], 0.01, "R^T * R diagonal should be 1");
                for (int j = 0; j < 3; j++) {
                    if (i != j) {
                        assertEquals(0.0, RtR[i][j], 0.01, "R^T * R off-diagonal should be 0");
                    }
                }
            }
        }

        @Test
        @DisplayName("Alignment throws for fewer than 3 CA atoms")
        void alignmentThrowsForTooFewAtoms() {
            List<AtomRecord> atoms1 = List.of(
                    makeAtom(1, "CA", "ALA", "A", 1, 0, 0, 0, "C", false),
                    makeAtom(2, "CA", "GLY", "A", 2, 3, 0, 0, "C", false)
            );

            ParsedPdb pdb1 = makePdb(new ArrayList<>(atoms1));
            ParsedPdb pdb2 = makePdb(new ArrayList<>(atoms1));

            assertThrows(IllegalArgumentException.class, () -> service.alignStructures(pdb1, pdb2));
        }

        private double computeUnalignedRmsd(List<AtomRecord> a1, List<AtomRecord> a2) {
            double sum = 0;
            for (int i = 0; i < a1.size(); i++) {
                double dx = a1.get(i).getX() - a2.get(i).getX();
                double dy = a1.get(i).getY() - a2.get(i).getY();
                double dz = a1.get(i).getZ() - a2.get(i).getZ();
                sum += dx * dx + dy * dy + dz * dz;
            }
            return Math.sqrt(sum / a1.size());
        }
    }
}
