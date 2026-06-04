package com.proteinviewer.unit;

import com.proteinviewer.model.AtomRecord;
import com.proteinviewer.model.ParsedPdb;
import com.proteinviewer.util.PdbParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdbParserPerformanceTest {

    private static final int TARGET_ATOMS = 300000;
    private static final int MAX_PARSE_TIME_SECONDS = 10;
    private static final int TARGET_PARSE_TIME_SECONDS = 5;

    private PdbParser parser;

    @BeforeEach
    void setUp() {
        parser = new PdbParser();
    }

    @Test
    @DisplayName("300K atoms should parse in under 10 seconds")
    void parse300kAtomsUnder10Seconds() throws IOException {
        String pdbContent = generateLargePdb(TARGET_ATOMS);
        long startTime = System.nanoTime();

        ParsedPdb result = parser.parse(new ByteArrayInputStream(pdbContent.getBytes(StandardCharsets.UTF_8)));

        long endTime = System.nanoTime();
        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;

        assertEquals(TARGET_ATOMS, result.getTotalAtoms(), "Should parse all atoms");
        assertTrue(elapsedSeconds < MAX_PARSE_TIME_SECONDS,
                String.format("Parsing took %.2f seconds, should be under %d seconds", elapsedSeconds, MAX_PARSE_TIME_SECONDS));

        System.out.printf("300K atom parse time: %.2f seconds (%.0f atoms/sec)%n",
                elapsedSeconds, TARGET_ATOMS / elapsedSeconds);
    }

    @Test
    @DisplayName("Benchmark mode returns accurate performance statistics")
    void benchmarkModeReturnsStatistics() throws IOException {
        String pdbContent = generateLargePdb(TARGET_ATOMS);

        PdbParser.ParseBenchmarkResult result = parser.benchmarkParse(
                new ByteArrayInputStream(pdbContent.getBytes(StandardCharsets.UTF_8)));

        assertEquals(TARGET_ATOMS, result.getAtomCount());
        assertTrue(result.getResidueCount() > 0, "Should count residues");
        assertTrue(result.getParseTimeMs() > 0, "Should measure parse time");
        assertTrue(result.getAtomsPerSecond() > 0, "Should calculate atoms per second");

        System.out.printf("Benchmark result: %d atoms in %.2f ms (%.0f atoms/sec, %d residues)%n",
                result.getAtomCount(), result.getParseTimeMs(), result.getAtomsPerSecond(), result.getResidueCount());

        assertTrue(result.getParseTimeMs() < MAX_PARSE_TIME_SECONDS * 1000,
                String.format("Benchmark parsing should be under %d seconds", MAX_PARSE_TIME_SECONDS));
    }

    @Test
    @DisplayName("Streaming parse callback processes all atoms without holding them in memory")
    void streamingParseCallback() throws IOException {
        String pdbContent = generateLargePdb(TARGET_ATOMS);
        AtomicInteger atomCount = new AtomicInteger(0);
        List<Double> xCoordinates = new ArrayList<>(1000);

        long startTime = System.nanoTime();
        parser.parseStream(
                new ByteArrayInputStream(pdbContent.getBytes(StandardCharsets.UTF_8)),
                atom -> {
                    atomCount.incrementAndGet();
                    if (xCoordinates.size() < 1000) {
                        xCoordinates.add(atom.getX());
                    }
                }
        );
        long endTime = System.nanoTime();

        double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;

        assertEquals(TARGET_ATOMS, atomCount.get(), "Stream should process all atoms");
        assertEquals(1000, xCoordinates.size(), "Should collect sample coordinates");

        System.out.printf("Streaming parse time: %.2f seconds (%.0f atoms/sec)%n",
                elapsedSeconds, TARGET_ATOMS / elapsedSeconds);

        assertTrue(elapsedSeconds < MAX_PARSE_TIME_SECONDS,
                String.format("Streaming parse took %.2f seconds, should be under %d seconds",
                        elapsedSeconds, MAX_PARSE_TIME_SECONDS));
    }

    @Test
    @DisplayName("Performance comparison: optimized parser vs expected baseline")
    void performanceComparison() throws IOException {
        String pdbContent = generateLargePdb(TARGET_ATOMS);

        PdbParser.ParseBenchmarkResult result = parser.benchmarkParse(
                new ByteArrayInputStream(pdbContent.getBytes(StandardCharsets.UTF_8)));

        double expectedAtomsPerSecond = TARGET_ATOMS / TARGET_PARSE_TIME_SECONDS;
        double actualAtomsPerSecond = result.getAtomsPerSecond();

        System.out.printf("Performance: %.0f atoms/sec (target: %.0f atoms/sec)%n",
                actualAtomsPerSecond, expectedAtomsPerSecond);
        System.out.printf("Total time: %.2f seconds (target: %d seconds)%n",
                result.getParseTimeMs() / 1000.0, TARGET_PARSE_TIME_SECONDS);

        assertTrue(result.getParseTimeMs() < MAX_PARSE_TIME_SECONDS * 1000,
                "Must complete within 10 seconds");

        if (actualAtomsPerSecond >= expectedAtomsPerSecond) {
            System.out.println("✓ Target performance achieved!");
        } else {
            System.out.printf("Note: Performance at %.1f%% of target%n",
                    100.0 * actualAtomsPerSecond / expectedAtomsPerSecond);
        }
    }

    @Test
    @DisplayName("Verify correctness of parsed data in large files")
    void verifyParsedDataCorrectness() throws IOException {
        String pdbContent = generateLargePdb(50000);
        ParsedPdb result = parser.parse(new ByteArrayInputStream(pdbContent.getBytes(StandardCharsets.UTF_8)));

        assertEquals(50000, result.getTotalAtoms());

        for (int i = 0; i < Math.min(100, result.getTotalAtoms()); i++) {
            AtomRecord atom = result.getAtoms().get(i);
            assertEquals(i + 1, atom.getSerialNumber(), "Serial numbers should be sequential");
            assertEquals("A", atom.getChainId(), "All atoms should be in chain A");
            assertTrue(atom.getX() >= -9999.0 && atom.getX() <= 9999.0, "X coordinate should be valid");
            assertTrue(atom.getY() >= -9999.0 && atom.getY() <= 9999.0, "Y coordinate should be valid");
            assertTrue(atom.getZ() >= -9999.0 && atom.getZ() <= 9999.0, "Z coordinate should be valid");
            assertEquals("C", atom.getElement(), "Element should be carbon");
        }

        assertTrue(result.getValidation().getWarnings().size() < 10,
                "Should have very few validation warnings, got: " + result.getValidation().getWarnings().size());
    }

    private String generateLargePdb(int atomCount) {
        StringBuilder sb = new StringBuilder(atomCount * 80 + 200);
        sb.append("HEADER    LARGE STRUCTURE TEST                     01-JAN-24   LARG\n");
        sb.append("TITLE     TEST STRUCTURE WITH ").append(atomCount).append(" ATOMS\n");

        String[] residueNames = {"ALA", "VAL", "LEU", "ILE", "SER", "THR", "CYS", "MET", "ASN", "GLN"};
        String[] atomNames = {" N  ", " CA ", " C  ", " O  ", " CB "};

        int residueNum = 1;
        int atomInResidue = 0;

        for (int i = 1; i <= atomCount; i++) {
            String resName = residueNames[(residueNum - 1) % residueNames.length];
            String atomName = atomNames[atomInResidue % atomNames.length];
            int serial = ((i - 1) % 99999) + 1;

            double x = (i % 1000) * 0.5;
            double y = ((i / 1000) % 100) * 0.5;
            double z = (i / 100000) * 0.5;

            sb.append(String.format("ATOM  %5d %s %s A%4d    %8.3f%8.3f%8.3f  1.00 20.00           C\n",
                    serial, atomName, resName, residueNum, x, y, z));

            atomInResidue++;
            if (atomInResidue >= 5) {
                atomInResidue = 0;
                residueNum++;
            }
        }

        sb.append("END\n");
        return sb.toString();
    }
}
