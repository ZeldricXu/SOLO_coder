package com.proteinviewer.dto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BinaryStructureData {

    public static final int MAGIC = 0x50444233;
    public static final short VERSION = 1;
    public static final int HEADER_SIZE = 14;
    public static final int ATOM_RECORD_SIZE = 18;
    public static final int BOND_RECORD_SIZE = 5;

    private static final Map<String, Byte> ELEMENT_TO_CODE = new HashMap<>();
    private static final Map<Byte, String> CODE_TO_ELEMENT = new HashMap<>();

    static {
        String[] elements = {"", "H", "C", "N", "O", "F", "P", "S", "CL", "BR", "I", "CA", "FE", "MG", "ZN", "NA", "K"};
        for (int i = 0; i < elements.length; i++) {
            ELEMENT_TO_CODE.put(elements[i], (byte) i);
            CODE_TO_ELEMENT.put((byte) i, elements[i]);
        }
    }

    private BinaryStructureData() {
    }

    public static byte[] toBinaryBytes(PdbDataDto dto) {
        List<AtomInfoDto> atoms = dto.getAtoms() != null ? dto.getAtoms() : new ArrayList<>();
        List<BondInfoDto> bonds = dto.getBonds() != null ? dto.getBonds() : new ArrayList<>();

        int atomCount = atoms.size();
        int totalBondPairs = 0;
        for (BondInfoDto bond : bonds) {
            if (bond.getBondedAtoms() != null) {
                totalBondPairs += bond.getBondedAtoms().size();
            }
        }

        int totalSize = HEADER_SIZE + atomCount * ATOM_RECORD_SIZE + totalBondPairs * BOND_RECORD_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(MAGIC);
        buffer.putShort(VERSION);
        buffer.putInt(atomCount);
        buffer.putInt(totalBondPairs);

        for (AtomInfoDto atom : atoms) {
            buffer.putShort((short) atom.getSerialNumber());
            buffer.putFloat((float) atom.getX());
            buffer.putFloat((float) atom.getY());
            buffer.putFloat((float) atom.getZ());
            buffer.put(getElementCode(atom.getElement()));
            buffer.putShort((short) (atom.getTempFactor() * 100.0));
            byte flags = 0;
            if (atom.isHetatm()) flags |= 0x01;
            buffer.put(flags);
        }

        for (BondInfoDto bond : bonds) {
            if (bond.getBondedAtoms() != null) {
                int atom1 = bond.getAtomSerial();
                for (Integer atom2 : bond.getBondedAtoms()) {
                    buffer.putShort((short) atom1);
                    buffer.putShort((short) atom2.intValue());
                    buffer.put((byte) 1);
                }
            }
        }

        return buffer.array();
    }

    public static PdbDataDto fromBinaryBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic number: expected 0x" + Integer.toHexString(MAGIC) + ", got 0x" + Integer.toHexString(magic));
        }

        short version = buffer.getShort();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        int atomCount = buffer.getInt();
        int bondCount = buffer.getInt();

        List<AtomInfoDto> atoms = new ArrayList<>(atomCount);
        for (int i = 0; i < atomCount; i++) {
            int serial = buffer.getShort() & 0xFFFF;
            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();
            byte elementCode = buffer.get();
            short tempFactorFixed = buffer.getShort();
            byte flags = buffer.get();

            AtomInfoDto atom = AtomInfoDto.builder()
                    .serialNumber(serial)
                    .x(x)
                    .y(y)
                    .z(z)
                    .element(getElementFromCode(elementCode))
                    .tempFactor((tempFactorFixed & 0xFFFF) / 100.0)
                    .isHetatm((flags & 0x01) != 0)
                    .build();
            atoms.add(atom);
        }

        Map<Integer, List<Integer>> bondMap = new HashMap<>();
        for (int i = 0; i < bondCount; i++) {
            int atom1 = buffer.getShort() & 0xFFFF;
            int atom2 = buffer.getShort() & 0xFFFF;
            byte order = buffer.get();

            bondMap.computeIfAbsent(atom1, k -> new ArrayList<>()).add(atom2);
        }

        List<BondInfoDto> bonds = new ArrayList<>(bondMap.size());
        for (Map.Entry<Integer, List<Integer>> entry : bondMap.entrySet()) {
            bonds.add(BondInfoDto.builder()
                    .atomSerial(entry.getKey())
                    .bondedAtoms(entry.getValue())
                    .build());
        }

        return PdbDataDto.builder()
                .atoms(atoms)
                .bonds(bonds)
                .totalAtoms(atomCount)
                .build();
    }

    private static byte getElementCode(String element) {
        if (element == null) return 0;
        String upper = element.toUpperCase().trim();
        return ELEMENT_TO_CODE.getOrDefault(upper, (byte) 0);
    }

    private static String getElementFromCode(byte code) {
        return CODE_TO_ELEMENT.getOrDefault(code, "");
    }
}
