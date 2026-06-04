package com.proteinviewer.unit;

import com.proteinviewer.dto.AtomInfoDto;
import com.proteinviewer.dto.BinaryStructureData;
import com.proteinviewer.dto.BondInfoDto;
import com.proteinviewer.dto.PdbDataDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BinaryStructureDataTest {

    private static final double FLOAT_DELTA = 1e-5;

    @Nested
    @DisplayName("Binary Serialization Tests")
    class BinarySerializationTests {

        @Test
        @DisplayName("Empty structure serializes and deserializes correctly")
        void emptyStructureRoundTrip() {
            PdbDataDto original = PdbDataDto.builder()
                    .atoms(new ArrayList<>())
                    .bonds(new ArrayList<>())
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(original);
            PdbDataDto deserialized = BinaryStructureData.fromBinaryBytes(binary);

            assertNotNull(deserialized);
            assertEquals(0, deserialized.getAtoms().size());
            assertEquals(0, deserialized.getBonds().size());
        }

        @Test
        @DisplayName("Single atom round-trips with correct coordinates")
        void singleAtomRoundTrip() {
            AtomInfoDto atom = AtomInfoDto.builder()
                    .serialNumber(1)
                    .x(10.123456)
                    .y(20.654321)
                    .z(30.111111)
                    .element("C")
                    .tempFactor(50.5)
                    .isHetatm(false)
                    .build();

            PdbDataDto original = PdbDataDto.builder()
                    .atoms(Arrays.asList(atom))
                    .bonds(new ArrayList<>())
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(original);
            PdbDataDto deserialized = BinaryStructureData.fromBinaryBytes(binary);

            assertEquals(1, deserialized.getAtoms().size());
            AtomInfoDto resultAtom = deserialized.getAtoms().get(0);
            assertEquals(1, resultAtom.getSerialNumber());
            assertEquals(10.123456, resultAtom.getX(), FLOAT_DELTA);
            assertEquals(20.654321, resultAtom.getY(), FLOAT_DELTA);
            assertEquals(30.111111, resultAtom.getZ(), FLOAT_DELTA);
            assertEquals("C", resultAtom.getElement());
            assertEquals(50.5, resultAtom.getTempFactor(), FLOAT_DELTA);
            assertFalse(resultAtom.isHetatm());
        }

        @Test
        @DisplayName("Multiple atoms with different elements round-trip correctly")
        void multipleAtomsRoundTrip() {
            List<AtomInfoDto> atoms = Arrays.asList(
                    AtomInfoDto.builder().serialNumber(1).x(1.1).y(2.2).z(3.3).element("C").tempFactor(20.0).isHetatm(false).build(),
                    AtomInfoDto.builder().serialNumber(2).x(4.4).y(5.5).z(6.6).element("N").tempFactor(30.0).isHetatm(false).build(),
                    AtomInfoDto.builder().serialNumber(3).x(7.7).y(8.8).z(9.9).element("O").tempFactor(40.0).isHetatm(true).build(),
                    AtomInfoDto.builder().serialNumber(4).x(10.0).y(11.0).z(12.0).element("FE").tempFactor(50.0).isHetatm(true).build()
            );

            PdbDataDto original = PdbDataDto.builder()
                    .atoms(atoms)
                    .bonds(new ArrayList<>())
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(original);
            PdbDataDto deserialized = BinaryStructureData.fromBinaryBytes(binary);

            assertEquals(4, deserialized.getAtoms().size());
            for (int i = 0; i < atoms.size(); i++) {
                AtomInfoDto orig = atoms.get(i);
                AtomInfoDto result = deserialized.getAtoms().get(i);
                assertEquals(orig.getSerialNumber(), result.getSerialNumber());
                assertEquals(orig.getX(), result.getX(), FLOAT_DELTA);
                assertEquals(orig.getY(), result.getY(), FLOAT_DELTA);
                assertEquals(orig.getZ(), result.getZ(), FLOAT_DELTA);
                assertEquals(orig.getElement(), result.getElement());
                assertEquals(orig.getTempFactor(), result.getTempFactor(), FLOAT_DELTA);
                assertEquals(orig.isHetatm(), result.isHetatm());
            }
        }

        @Test
        @DisplayName("Bonds round-trip correctly")
        void bondsRoundTrip() {
            List<AtomInfoDto> atoms = Arrays.asList(
                    AtomInfoDto.builder().serialNumber(1).x(0).y(0).z(0).element("C").build(),
                    AtomInfoDto.builder().serialNumber(2).x(1).y(0).z(0).element("C").build(),
                    AtomInfoDto.builder().serialNumber(3).x(2).y(0).z(0).element("C").build()
            );

            List<BondInfoDto> bonds = Arrays.asList(
                    BondInfoDto.builder().atomSerial(1).bondedAtoms(Arrays.asList(2)).build(),
                    BondInfoDto.builder().atomSerial(2).bondedAtoms(Arrays.asList(1, 3)).build(),
                    BondInfoDto.builder().atomSerial(3).bondedAtoms(Arrays.asList(2)).build()
            );

            PdbDataDto original = PdbDataDto.builder()
                    .atoms(atoms)
                    .bonds(bonds)
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(original);
            PdbDataDto deserialized = BinaryStructureData.fromBinaryBytes(binary);

            assertEquals(3, deserialized.getBonds().size());
        }

        @Test
        @DisplayName("Negative coordinates round-trip correctly")
        void negativeCoordinatesRoundTrip() {
            AtomInfoDto atom = AtomInfoDto.builder()
                    .serialNumber(1)
                    .x(-10.5)
                    .y(-20.25)
                    .z(-30.125)
                    .element("C")
                    .tempFactor(0.0)
                    .build();

            PdbDataDto original = PdbDataDto.builder()
                    .atoms(Arrays.asList(atom))
                    .bonds(new ArrayList<>())
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(original);
            PdbDataDto deserialized = BinaryStructureData.fromBinaryBytes(binary);

            AtomInfoDto result = deserialized.getAtoms().get(0);
            assertEquals(-10.5, result.getX(), FLOAT_DELTA);
            assertEquals(-20.25, result.getY(), FLOAT_DELTA);
            assertEquals(-30.125, result.getZ(), FLOAT_DELTA);
        }

        @Test
        @DisplayName("Large structure demonstrates size efficiency")
        void largeStructureSizeEfficiency() {
            int atomCount = 300000;
            List<AtomInfoDto> atoms = new ArrayList<>(atomCount);
            for (int i = 0; i < atomCount; i++) {
                atoms.add(AtomInfoDto.builder()
                        .serialNumber(i + 1)
                        .x(i * 0.1)
                        .y(i * 0.2)
                        .z(i * 0.3)
                        .element("C")
                        .tempFactor(50.0)
                        .build());
            }

            int bondCount = atomCount / 2;
            List<BondInfoDto> bonds = new ArrayList<>();
            for (int i = 0; i < bondCount; i++) {
                bonds.add(BondInfoDto.builder()
                        .atomSerial(i + 1)
                        .bondedAtoms(Arrays.asList(i + 2))
                        .build());
            }

            PdbDataDto dto = PdbDataDto.builder()
                    .atoms(atoms)
                    .bonds(bonds)
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(dto);

            int expectedSize = BinaryStructureData.HEADER_SIZE
                    + atomCount * BinaryStructureData.ATOM_RECORD_SIZE
                    + bondCount * BinaryStructureData.BOND_RECORD_SIZE;

            assertEquals(expectedSize, binary.length);
            assertTrue(binary.length < 7_000_000, "Binary should be under 7MB for 300k atoms, was: " + binary.length);
        }

        @Test
        @DisplayName("Invalid magic number throws exception")
        void invalidMagicNumber() {
            byte[] invalid = new byte[14];
            invalid[0] = 0x00;
            invalid[1] = 0x00;
            invalid[2] = 0x00;
            invalid[3] = 0x00;

            assertThrows(IllegalArgumentException.class, () -> {
                BinaryStructureData.fromBinaryBytes(invalid);
            });
        }

        @Test
        @DisplayName("Header has correct magic and version")
        void headerHasCorrectMagicAndVersion() {
            PdbDataDto dto = PdbDataDto.builder()
                    .atoms(new ArrayList<>())
                    .bonds(new ArrayList<>())
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(dto);

            int magic = (binary[0] & 0xFF) | ((binary[1] & 0xFF) << 8) | ((binary[2] & 0xFF) << 16) | ((binary[3] & 0xFF) << 24);
            short version = (short) ((binary[4] & 0xFF) | ((binary[5] & 0xFF) << 8));

            assertEquals(BinaryStructureData.MAGIC, magic);
            assertEquals(BinaryStructureData.VERSION, version);
        }

        @Test
        @DisplayName("Hetatm flag is preserved in binary format")
        void hetatmFlagPreserved() {
            List<AtomInfoDto> atoms = Arrays.asList(
                    AtomInfoDto.builder().serialNumber(1).x(0).y(0).z(0).element("C").isHetatm(false).build(),
                    AtomInfoDto.builder().serialNumber(2).x(1).y(1).z(1).element("FE").isHetatm(true).build()
            );

            PdbDataDto original = PdbDataDto.builder()
                    .atoms(atoms)
                    .bonds(new ArrayList<>())
                    .build();

            byte[] binary = BinaryStructureData.toBinaryBytes(original);
            PdbDataDto deserialized = BinaryStructureData.fromBinaryBytes(binary);

            assertFalse(deserialized.getAtoms().get(0).isHetatm());
            assertTrue(deserialized.getAtoms().get(1).isHetatm());
        }
    }
}
