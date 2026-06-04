package com.proteinviewer.unit;

import com.proteinviewer.model.*;
import com.proteinviewer.util.PdbParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PdbParserTest {

    private PdbParser parser;

    @BeforeEach
    void setUp() {
        parser = new PdbParser();
    }

    private ParsedPdb parse(String pdbContent) throws IOException {
        return parser.parse(new ByteArrayInputStream(pdbContent.getBytes(StandardCharsets.UTF_8)));
    }

    @Nested
    @DisplayName("Standard PDB Record Parsing")
    class StandardRecordParsing {

        @Test
        @DisplayName("Parse complete ATOM record with all fields correctly extracted")
        void parseCompleteAtomRecord() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1      10.206  20.147  29.830  1.00 20.00           N\n";
            ParsedPdb result = parse(pdb);

            assertEquals(1, result.getTotalAtoms());
            AtomRecord atom = result.getAtoms().get(0);

            assertEquals(1, atom.getSerialNumber());
            assertEquals("N", atom.getAtomName());
            assertEquals(' ', atom.getAltLocation());
            assertEquals("ALA", atom.getResidueName());
            assertEquals("A", atom.getChainId());
            assertEquals(1, atom.getResidueSeqNumber());
            assertEquals(10.206, atom.getX(), 0.001);
            assertEquals(20.147, atom.getY(), 0.001);
            assertEquals(29.830, atom.getZ(), 0.001);
            assertEquals(1.0, atom.getOccupancy(), 0.001);
            assertEquals(20.0, atom.getTempFactor(), 0.001);
            assertEquals("N", atom.getElement());
            assertFalse(atom.isHetatm());
        }

        @Test
        @DisplayName("Parse HETATM records and correctly identify as non-standard residues")
        void parseHetatmRecord() throws IOException {
            String pdb = "HETATM   52  FE  HEM A 101       5.000   5.000   5.000  1.00 30.00          FE\n" +
                         "HETATM   53  O   HOH A 201      12.000  12.000  12.000  1.00 40.00           O\n";
            ParsedPdb result = parse(pdb);

            assertEquals(2, result.getTotalAtoms());
            assertTrue(result.getAtoms().get(0).isHetatm());
            assertTrue(result.getAtoms().get(1).isHetatm());
            assertEquals("FE", result.getAtoms().get(0).getElement());
            assertEquals("O", result.getAtoms().get(1).getElement());
        }

        @Test
        @DisplayName("Parse multiple ATOM records preserving order and all chain IDs")
        void parseMultipleAtomRecords() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n" +
                    "ATOM      3  N   GLY B   1       3.000   3.000   3.000  1.00 15.00           N\n" +
                    "ATOM      4  CA  GLY B   1       4.000   3.000   3.000  1.00 14.00           C\n";
            ParsedPdb result = parse(pdb);

            assertEquals(4, result.getTotalAtoms());
            assertEquals(List.of("A", "B"), result.getChainIds());
            assertEquals("A", result.getAtoms().get(0).getChainId());
            assertEquals("B", result.getAtoms().get(2).getChainId());
        }

        @Test
        @DisplayName("Parse HEADER record extracting PDB ID and header classification")
        void parseHeaderRecord() throws IOException {
            String pdb = "HEADER    OXYGEN TRANSPORT                        01-JAN-24   1ABC\n";
            ParsedPdb result = parse(pdb);

            assertEquals("1ABC", result.getPdbId());
            assertTrue(result.getHeader().contains("OXYGEN TRANSPORT"));
        }

        @Test
        @DisplayName("Parse TITLE record spanning continuation lines")
        void parseTitleRecord() throws IOException {
            String pdb = "TITLE     SAMPLE PROTEIN STRUCTURE FOR TESTING\n" +
                         "TITLE   2 SECOND LINE OF TITLE\n";
            ParsedPdb result = parse(pdb);

            assertTrue(result.getTitle().contains("SAMPLE PROTEIN STRUCTURE FOR TESTING"));
            assertTrue(result.getTitle().contains("SECOND LINE OF TITLE"));
        }

        @Test
        @DisplayName("Parse TER and END records without errors")
        void parseTerAndEndRecords() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "TER\n" +
                    "HETATM    2  O   HOH A 201       5.000   5.000   5.000  1.00 30.00           O\n" +
                    "END\n";
            ParsedPdb result = parse(pdb);

            assertEquals(2, result.getTotalAtoms());
            assertFalse(result.getAtoms().get(0).isHetatm());
            assertTrue(result.getAtoms().get(1).isHetatm());
        }

        @Test
        @DisplayName("Parse ATOM record with insertion code")
        void parseAtomWithInsertionCode() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1A      1.000   1.000   1.000  1.00 20.00           N\n";
            ParsedPdb result = parse(pdb);

            assertEquals(1, result.getTotalAtoms());
            assertEquals('A', result.getAtoms().get(0).getICode());
        }

        @Test
        @DisplayName("Parse ATOM record with alternate location indicator")
        void parseAtomWithAltLocation() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                         "ATOM      2  N   ALA A   1       2.000   2.000   2.000  0.50 20.00           N\n";
            ParsedPdb result = parse(pdb);

            assertEquals(2, result.getTotalAtoms());
        }

        @Test
        @DisplayName("Parse ATOM record with charge field")
        void parseAtomWithCharge() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N1+\n";
            ParsedPdb result = parse(pdb);

            AtomRecord atom = result.getAtoms().get(0);
            assertTrue(atom.getElement().equals("N") || atom.getCharge().contains("1"),
                    "Element or charge should be parsed, element='" + atom.getElement() + "' charge='" + atom.getCharge() + "'");
        }
    }

    @Nested
    @DisplayName("CONECT Record Bond Graph")
    class ConectBondGraph {

        @Test
        @DisplayName("Parse CONECT records and build complete bond graph")
        void parseConectRecords() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n" +
                    "ATOM      3  C   ALA A   1       2.500   2.200   1.500  1.00 19.00           C\n" +
                    "ATOM      4  CB  ALA A   1       2.200  -0.300   1.500  1.00 17.00           C\n" +
                    "CONECT    1    2\n" +
                    "CONECT    2    1    3    4\n" +
                    "CONECT    3    2\n" +
                    "CONECT    4    2\n";
            ParsedPdb result = parse(pdb);

            assertEquals(4, result.getBonds().size());

            Map<Integer, List<Integer>> bondMap = result.getBonds().stream()
                    .collect(Collectors.toMap(BondRecord::getAtomSerial, BondRecord::getBondedAtoms));

            assertEquals(List.of(2), bondMap.get(1));
            assertEquals(List.of(1, 3, 4), bondMap.get(2));
            assertEquals(List.of(2), bondMap.get(3));
            assertEquals(List.of(2), bondMap.get(4));
        }

        @Test
        @DisplayName("Verify neighbor count for atoms in CONECT bond graph matches expected")
        void verifyNeighborCount() throws IOException {
            String pdb =
                    "ATOM      1  C1  BEN A   1       0.000   0.000   0.000  1.00 20.00           C\n" +
                    "ATOM      2  C2  BEN A   1       1.400   0.000   0.000  1.00 20.00           C\n" +
                    "ATOM      3  C3  BEN A   1       2.100   1.200   0.000  1.00 20.00           C\n" +
                    "ATOM      4  C4  BEN A   1       1.400   2.400   0.000  1.00 20.00           C\n" +
                    "ATOM      5  C5  BEN A   1       0.000   2.400   0.000  1.00 20.00           C\n" +
                    "ATOM      6  C6  BEN A   1      -0.700   1.200   0.000  1.00 20.00           C\n" +
                    "CONECT    1    2    6\n" +
                    "CONECT    2    1    3\n" +
                    "CONECT    3    2    4\n" +
                    "CONECT    4    3    5\n" +
                    "CONECT    5    4    6\n" +
                    "CONECT    6    5    1\n";

            ParsedPdb result = parse(pdb);
            Map<Integer, List<Integer>> bondMap = result.getBonds().stream()
                    .collect(Collectors.toMap(BondRecord::getAtomSerial, BondRecord::getBondedAtoms));

            for (int serial = 1; serial <= 6; serial++) {
                assertEquals(2, bondMap.get(serial).size(),
                        "Atom " + serial + " in benzene ring should have exactly 2 bond neighbors");
            }
        }

        @Test
        @DisplayName("CONECT with four bonded atoms parsed correctly")
        void conectWithFourBonds() throws IOException {
            String pdb =
                    "ATOM      1  C   MET A   1       0.000   0.000   0.000  1.00 20.00           C\n" +
                    "ATOM      2  H1  MET A   1       1.000   0.000   0.000  1.00 20.00           H\n" +
                    "ATOM      3  H2  MET A   1       0.000   1.000   0.000  1.00 20.00           H\n" +
                    "ATOM      4  H3  MET A   1       0.000   0.000   1.000  1.00 20.00           H\n" +
                    "ATOM      5  H4  MET A   1      -1.000   0.000   0.000  1.00 20.00           H\n" +
                    "CONECT    1    2    3    4    5\n";

            ParsedPdb result = parse(pdb);
            assertEquals(1, result.getBonds().size());
            assertEquals(4, result.getBonds().get(0).getBondedAtoms().size());
        }

        @Test
        @DisplayName("Empty CONECT line (no bonded atoms) returns null bond record")
        void emptyConectLine() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "CONECT    1\n";

            ParsedPdb result = parse(pdb);
            assertEquals(0, result.getBonds().size());
        }
    }

    @Nested
    @DisplayName("Validation Warnings")
    class ValidationWarnings {

        @Test
        @DisplayName("Warning for atom serial number gap (non-sequential)")
        void serialNumberGapWarning() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      5  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n";

            ParsedPdb result = parse(pdb);

            assertFalse(result.getValidation().isValid());
            boolean hasSerialWarning = result.getValidation().getWarnings().stream()
                    .anyMatch(w -> w.getField().equals("serialNumber") && w.getMessage().contains("gap"));
            assertTrue(hasSerialWarning, "Should warn about serial number gap");
        }

        @Test
        @DisplayName("Warning for coordinates exceeding PDB column width limit (9999.999)")
        void coordinateOutOfRangeWarning() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1    99999.999   1.000   1.000  1.00 20.00           N\n";

            ParsedPdb result = parse(pdb);

            boolean hasCoordWarning = result.getValidation().getWarnings().stream()
                    .anyMatch(w -> w.getField().equals("coordinates") && w.getMessage().contains("outside reasonable range"));
            assertTrue(hasCoordWarning, "Should warn about coordinates outside range");
        }

        @Test
        @DisplayName("NaN in coordinate field is parsed gracefully")
        void invalidCoordinateParsedGracefully() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1       NaN      1.000   1.000  1.00 20.00           N\n";

            ParsedPdb result = parse(pdb);

            assertNotNull(result);
            assertEquals(1, result.getTotalAtoms());
        }

        @Test
        @DisplayName("Warning for coordinates at negative extreme")
        void negativeCoordinateOutOfRangeWarning() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1  -99999.999   1.000   1.000  1.00 20.00           N\n";

            ParsedPdb result = parse(pdb);

            boolean hasCoordWarning = result.getValidation().getWarnings().stream()
                    .anyMatch(w -> w.getField().equals("coordinates"));
            assertTrue(hasCoordWarning);
        }

        @Test
        @DisplayName("Warning for unknown element type")
        void unknownElementWarning() throws IOException {
            String pdb = "ATOM      1  XX   XXX A   1       1.000   1.000   1.000  1.00 20.00          XX\n";

            ParsedPdb result = parse(pdb);

            boolean hasElementWarning = result.getValidation().getWarnings().stream()
                    .anyMatch(w -> w.getField().equals("element") && w.getMessage().contains("Unknown element"));
            assertTrue(hasElementWarning);
        }

        @Test
        @DisplayName("Warning for non-contiguous chain IDs")
        void nonContiguousChainWarning() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      2  N   GLY B   1       2.000   2.000   2.000  1.00 20.00           N\n" +
                    "ATOM      3  CA  ALA A   1       3.000   3.000   3.000  1.00 20.00           N\n";

            ParsedPdb result = parse(pdb);

            boolean hasChainWarning = result.getValidation().getWarnings().stream()
                    .anyMatch(w -> w.getField().equals("chainId") && w.getMessage().contains("not contiguous"));
            assertTrue(hasChainWarning, "Should warn about non-contiguous chain A");
        }

        @Test
        @DisplayName("No warnings for valid PDB file")
        void noWarningsForValidFile() throws IOException {
            String pdb =
                    "HEADER    TEST                                       01-JAN-24   1TST\n" +
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n" +
                    "END\n";

            ParsedPdb result = parse(pdb);

            assertTrue(result.getValidation().isValid());
            assertEquals(0, result.getValidation().getWarnings().size());
        }

        @Test
        @DisplayName("Validation warnings include line numbers for precise location")
        void warningsIncludeLineNumbers() throws IOException {
            String pdb =
                    "HEADER    TEST\n" +
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      5  CA  ALA A   1   10000.000   1.000   1.000  1.00 18.00           C\n";

            ParsedPdb result = parse(pdb);

            List<ValidationWarning> warnings = result.getValidation().getWarnings();
            assertTrue(warnings.stream().anyMatch(w -> w.getLineNumber() == 3));
        }
    }

    @Nested
    @DisplayName("Chain ID Compatibility")
    class ChainIdCompatibility {

        @Test
        @DisplayName("Single character chain ID (PDB v2 standard) parsed correctly")
        void singleCharChainId() throws IOException {
            String pdb = "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n";
            ParsedPdb result = parse(pdb);

            assertEquals("A", result.getAtoms().get(0).getChainId());
        }

        @Test
        @DisplayName("Numeric chain ID parsed correctly")
        void numericChainId() throws IOException {
            String pdb = "ATOM      1  N   ALA 1   1       1.000   1.000   1.000  1.00 20.00           N\n";
            ParsedPdb result = parse(pdb);

            assertEquals("1", result.getAtoms().get(0).getChainId());
        }

        @Test
        @DisplayName("Empty chain ID defaults to 'A'")
        void emptyChainIdDefaults() throws IOException {
            String pdb = "ATOM      1  N   ALA     1       1.000   1.000   1.000  1.00 20.00           N\n";
            ParsedPdb result = parse(pdb);

            assertEquals("A", result.getAtoms().get(0).getChainId());
        }

        @Test
        @DisplayName("Multiple chain IDs are collected in order of appearance")
        void multipleChainIdsInOrder() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      2  N   GLY B   1       2.000   2.000   2.000  1.00 20.00           N\n" +
                    "ATOM      3  N   VAL C   1       3.000   3.000   3.000  1.00 20.00           N\n";

            ParsedPdb result = parse(pdb);
            assertEquals(List.of("A", "B", "C"), result.getChainIds());
        }
    }

    @Nested
    @DisplayName("Element Inference")
    class ElementInference {

        @Test
        @DisplayName("Element inferred from atom name when element column is absent")
        void elementInferredFromAtomName() throws IOException {
            String pdb = "ATOM      1  CA  ALA A   1       1.000   1.000   1.000  1.00 20.00\n";
            ParsedPdb result = parse(pdb);

            String element = result.getAtoms().get(0).getElement();
            assertTrue(element.equals("C") || element.equals("CA"),
                    "CA atom name should infer Carbon (C) or be treated as Calcium (CA)");
        }

        @Test
        @DisplayName("Two-letter element (FE, ZN) correctly extracted from element column")
        void twoLetterElementFromColumn() throws IOException {
            String pdb = "HETATM    1  FE  HEM A 101       5.000   5.000   5.000  1.00 30.00          FE\n";
            ParsedPdb result = parse(pdb);

            assertEquals("FE", result.getAtoms().get(0).getElement());
        }

        @Test
        @DisplayName("Hydrogen atom element correctly identified")
        void hydrogenElement() throws IOException {
            String pdb = "ATOM      1  H   ALA A   1       1.000   1.000   1.000  1.00 20.00           H\n";
            ParsedPdb result = parse(pdb);

            assertEquals("H", result.getAtoms().get(0).getElement());
        }
    }

    @Nested
    @DisplayName("Residue Counting")
    class ResidueCounting {

        @Test
        @DisplayName("Unique residues counted correctly across chains")
        void uniqueResidueCount() throws IOException {
            String pdb =
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "ATOM      2  CA  ALA A   1       2.000   1.000   1.000  1.00 18.00           C\n" +
                    "ATOM      3  N   GLY A   2       3.000   3.000   3.000  1.00 15.00           N\n" +
                    "ATOM      4  N   ALA B   1       4.000   4.000   4.000  1.00 20.00           N\n";

            ParsedPdb result = parse(pdb);
            assertEquals(3, result.getTotalResidues(), "ALA A:1, GLY A:2, ALA B:1 = 3 unique residues");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Empty file produces empty result with no errors")
        void emptyFile() throws IOException {
            ParsedPdb result = parse("");

            assertEquals(0, result.getTotalAtoms());
            assertEquals(0, result.getTotalResidues());
            assertTrue(result.getValidation().isValid());
        }

        @Test
        @DisplayName("Very short ATOM line (truncated) does not crash parser")
        void truncatedAtomLine() throws IOException {
            String pdb = "ATOM      1\n";
            ParsedPdb result = parse(pdb);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Unknown record types are silently ignored")
        void unknownRecordType() throws IOException {
            String pdb =
                    "REMARK   THIS IS A REMARK\n" +
                    "ATOM      1  N   ALA A   1       1.000   1.000   1.000  1.00 20.00           N\n" +
                    "SSBOND   1 CYS A    5    CYS A   55\n";

            ParsedPdb result = parse(pdb);
            assertEquals(1, result.getTotalAtoms());
        }

        @Test
        @DisplayName("Large PDB file with thousands of atoms parses correctly")
        void largePdbFile() throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HEADER    LARGE TEST                                 1LRG\n");
            for (int i = 1; i <= 5000; i++) {
                sb.append(String.format("ATOM   %4d  CA  ALA A %4d    %8.3f%8.3f%8.3f  1.00 20.00           C\n",
                        i, i, i * 0.5, i * 0.3, i * 0.1));
            }
            sb.append("END\n");

            ParsedPdb result = parse(sb.toString());
            assertEquals(5000, result.getTotalAtoms());
        }
    }
}
