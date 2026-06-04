package com.proteinviewer.util;

import com.proteinviewer.model.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

@Component
public class PdbParser {

    private static final double COORD_MIN = -9999.0;
    private static final double COORD_MAX = 9999.0;
    private static final Set<String> KNOWN_ELEMENTS = Set.of(
            "H", "C", "N", "O", "S", "P", "F", "CL", "BR", "I",
            "FE", "ZN", "CU", "MG", "MN", "CA", "NA", "K", "SE", "CO", "NI"
    );

    private static final int INITIAL_CAPACITY = 100000;
    private static final int CAPACITY_GROWTH_FACTOR = 2;

    public ParsedPdb parse(InputStream inputStream) throws IOException {
        List<AtomRecord> atoms = new ArrayList<>(INITIAL_CAPACITY);
        List<BondRecord> bonds = new ArrayList<>(INITIAL_CAPACITY / 10);
        List<ValidationWarning> warnings = new ArrayList<>();
        String pdbId = "";
        StringBuilder titleBuilder = new StringBuilder();
        String header = "";
        Set<String> chainIds = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            int lineNum = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                String recordType = parseRecordType(line);

                switch (recordType) {
                    case "HEADER":
                        header = parseHeader(line);
                        pdbId = parsePdbIdFromHeader(line);
                        break;
                    case "TITLE":
                        String titlePart = parseTitlePart(line);
                        if (titleBuilder.length() > 0) {
                            titleBuilder.append(' ');
                        }
                        titleBuilder.append(titlePart);
                        break;
                    case "ATOM":
                    case "HETATM":
                        AtomRecord atom = parseAtomLineFast(line, lineNum, "HETATM".equals(recordType));
                        if (atom != null) {
                            atoms.add(atom);
                            chainIds.add(atom.getChainId());
                        }
                        break;
                    case "CONECT":
                        BondRecord bond = parseConectLineFast(line, lineNum);
                        if (bond != null) {
                            bonds.add(bond);
                        }
                        break;
                }
            }

            performDeferredValidation(atoms, warnings);
            validateChainConsistency(atoms, warnings);
        }

        ValidationResult validation = new ValidationResult(warnings.isEmpty(), warnings);
        int residueCount = countResiduesLazy(atoms);

        return ParsedPdb.builder()
                .pdbId(pdbId)
                .title(titleBuilder.toString())
                .header(header)
                .atoms(atoms)
                .bonds(bonds)
                .validation(validation)
                .totalAtoms(atoms.size())
                .totalResidues(residueCount)
                .chainIds(new ArrayList<>(chainIds))
                .build();
    }

    public void parseStream(InputStream inputStream, Consumer<AtomRecord> atomConsumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            int lineNum = 0;

            while ((line = reader.readLine()) != null) {
                lineNum++;
                String recordType = parseRecordType(line);

                if ("ATOM".equals(recordType) || "HETATM".equals(recordType)) {
                    AtomRecord atom = parseAtomLineFast(line, lineNum, "HETATM".equals(recordType));
                    if (atom != null) {
                        atomConsumer.accept(atom);
                    }
                }
            }
        }
    }

    public ParseBenchmarkResult benchmarkParse(InputStream inputStream) throws IOException {
        long startTime = System.nanoTime();
        ParsedPdb result = parse(inputStream);
        long endTime = System.nanoTime();

        double elapsedMillis = (endTime - startTime) / 1_000_000.0;
        double atomsPerSecond = (result.getTotalAtoms() / (elapsedMillis / 1000.0));

        return new ParseBenchmarkResult(
                result.getTotalAtoms(),
                result.getTotalResidues(),
                result.getBonds().size(),
                elapsedMillis,
                atomsPerSecond
        );
    }

    public static class ParseBenchmarkResult {
        private final int atomCount;
        private final int residueCount;
        private final int bondCount;
        private final double parseTimeMs;
        private final double atomsPerSecond;

        public ParseBenchmarkResult(int atomCount, int residueCount, int bondCount, double parseTimeMs, double atomsPerSecond) {
            this.atomCount = atomCount;
            this.residueCount = residueCount;
            this.bondCount = bondCount;
            this.parseTimeMs = parseTimeMs;
            this.atomsPerSecond = atomsPerSecond;
        }

        public int getAtomCount() { return atomCount; }
        public int getResidueCount() { return residueCount; }
        public int getBondCount() { return bondCount; }
        public double getParseTimeMs() { return parseTimeMs; }
        public double getAtomsPerSecond() { return atomsPerSecond; }
    }

    private String parseRecordType(String line) {
        int len = line.length();
        if (len >= 6) {
            char[] chars = new char[6];
            int count = 0;
            for (int i = 0; i < 6; i++) {
                char c = line.charAt(i);
                if (c != ' ') {
                    chars[count++] = c;
                }
            }
            return new String(chars, 0, count);
        }
        return line.trim();
    }

    private String parseHeader(String line) {
        if (line.length() > 10) {
            int end = Math.min(50, line.length());
            return trimSubstring(line, 10, end);
        }
        return "";
    }

    private String parsePdbIdFromHeader(String line) {
        if (line.length() >= 66) {
            return trimSubstring(line, 62, 66);
        }
        return "";
    }

    private String parseTitlePart(String line) {
        if (line.length() > 10) {
            return trimSubstring(line, 10, line.length());
        }
        return "";
    }

    private String trimSubstring(String line, int start, int end) {
        int lineLen = line.length();
        if (lineLen <= start) return "";
        int s = start;
        int e = Math.min(end - 1, lineLen - 1);
        while (s < e && line.charAt(s) == ' ') s++;
        while (e > s && line.charAt(e) == ' ') e--;
        if (s > e) return "";
        return line.substring(s, e + 1);
    }

    private void performDeferredValidation(List<AtomRecord> atoms, List<ValidationWarning> warnings) {
        if (atoms.isEmpty()) return;

        int prevSerial = -1;
        for (AtomRecord atom : atoms) {
            int serial = atom.getSerialNumber();

            if (prevSerial >= 0 && serial != prevSerial + 1 && serial != prevSerial) {
                warnings.add(new ValidationWarning(atom.getLineNumber(), "serialNumber",
                        "Atom serial number gap: expected " + (prevSerial + 1) + " but found " + serial, "WARNING"));
            }
            prevSerial = serial;

            double x = atom.getX();
            double y = atom.getY();
            double z = atom.getZ();
            if (x < COORD_MIN || x > COORD_MAX || y < COORD_MIN || y > COORD_MAX || z < COORD_MIN || z > COORD_MAX) {
                warnings.add(new ValidationWarning(atom.getLineNumber(), "coordinates",
                        "Atom " + serial + " has coordinates outside reasonable range", "WARNING"));
            }

            if (!KNOWN_ELEMENTS.contains(atom.getElement().toUpperCase())) {
                warnings.add(new ValidationWarning(atom.getLineNumber(), "element",
                        "Unknown element type: " + atom.getElement(), "INFO"));
            }
        }
    }

    private int countResiduesLazy(List<AtomRecord> atoms) {
        if (atoms.isEmpty()) return 0;

        Set<String> seenResidues = new HashSet<>(atoms.size() / 10);
        int count = 0;

        for (AtomRecord atom : atoms) {
            String key = atom.getChainId() + ":" + atom.getResidueSeqNumber() + ":" + atom.getResidueName();
            if (!seenResidues.contains(key)) {
                seenResidues.add(key);
                count++;
            }
        }
        return count;
    }

    private AtomRecord parseAtomLineFast(String line, int lineNum, boolean isHetatm) {
        try {
            int lineLen = line.length();

            int serial = parseIntFast(line, 6, 11, lineLen);
            String atomName = trimSubstring(line, 12, 16);
            char altLoc = lineLen > 16 ? line.charAt(16) : ' ';
            String resName = trimSubstring(line, 17, 20);
            String chainId = lineLen > 21 ? String.valueOf(line.charAt(21)).trim() : "A";
            int resSeq = parseIntFast(line, 22, 26, lineLen);
            char iCode = lineLen > 26 ? line.charAt(26) : ' ';
            double x = parseDoubleFast(line, 30, 38, lineLen);
            double y = parseDoubleFast(line, 38, 46, lineLen);
            double z = parseDoubleFast(line, 46, 54, lineLen);
            double occupancy = lineLen > 54 ? parseDoubleFast(line, 54, 60, lineLen) : 1.0;
            double tempFactor = lineLen > 60 ? parseDoubleFast(line, 60, 66, lineLen) : 0.0;

            String element;
            if (lineLen > 76) {
                element = trimSubstring(line, 76, 78);
                if (element.isEmpty()) {
                    element = extractElementFromName(atomName);
                }
            } else {
                element = extractElementFromName(atomName);
            }

            String charge = lineLen > 78 ? trimSubstring(line, 78, 80) : "";

            return AtomRecord.builder()
                    .serialNumber(serial)
                    .atomName(atomName.trim())
                    .altLocation(altLoc)
                    .residueName(resName.trim())
                    .chainId(chainId.isEmpty() ? "A" : chainId)
                    .residueSeqNumber(resSeq)
                    .iCode(iCode)
                    .x(x)
                    .y(y)
                    .z(z)
                    .occupancy(occupancy)
                    .tempFactor(tempFactor)
                    .element(element.isEmpty() ? extractElementFromName(atomName) : element)
                    .charge(charge)
                    .lineNumber(lineNum)
                    .isHetatm(isHetatm)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private int parseIntFast(String line, int start, int end, int lineLen) {
        if (lineLen < end) return 0;

        int result = 0;
        boolean negative = false;
        boolean started = false;

        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                if (started) break;
                continue;
            }
            if (c == '-') {
                negative = true;
                started = true;
                continue;
            }
            if (c == '+') {
                started = true;
                continue;
            }
            if (c >= '0' && c <= '9') {
                result = result * 10 + (c - '0');
                started = true;
            } else {
                break;
            }
        }

        return negative ? -result : result;
    }

    private double parseDoubleFast(String line, int start, int end, int lineLen) {
        if (lineLen < end) return 0.0;

        long result = 0;
        boolean negative = false;
        boolean started = false;
        int decimalPlaces = 0;
        boolean decimalSeen = false;

        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                if (started) break;
                continue;
            }
            if (c == '-') {
                negative = true;
                started = true;
                continue;
            }
            if (c == '+') {
                started = true;
                continue;
            }
            if (c == '.') {
                decimalSeen = true;
                started = true;
                continue;
            }
            if (c >= '0' && c <= '9') {
                result = result * 10 + (c - '0');
                started = true;
                if (decimalSeen) {
                    decimalPlaces++;
                }
            } else {
                break;
            }
        }

        if (!started) return 0.0;

        double doubleResult = (double) result;
        if (decimalPlaces > 0) {
            double divisor = 1.0;
            for (int i = 0; i < decimalPlaces; i++) {
                divisor *= 10.0;
            }
            doubleResult /= divisor;
        }

        return negative ? -doubleResult : doubleResult;
    }

    private BondRecord parseConectLineFast(String line, int lineNum) {
        try {
            int lineLen = line.length();
            int atom1 = parseIntFast(line, 6, 11, lineLen);
            List<Integer> bonded = new ArrayList<>(4);

            if (lineLen >= 16) {
                int a2 = parseIntFast(line, 11, 16, lineLen);
                if (a2 > 0) bonded.add(a2);
            }
            if (lineLen >= 21) {
                int a3 = parseIntFast(line, 16, 21, lineLen);
                if (a3 > 0) bonded.add(a3);
            }
            if (lineLen >= 26) {
                int a4 = parseIntFast(line, 21, 26, lineLen);
                if (a4 > 0) bonded.add(a4);
            }
            if (lineLen >= 31) {
                int a5 = parseIntFast(line, 26, 31, lineLen);
                if (a5 > 0) bonded.add(a5);
            }

            if (bonded.isEmpty()) return null;
            return BondRecord.builder().atomSerial(atom1).bondedAtoms(bonded).lineNumber(lineNum).build();
        } catch (Exception e) {
            return null;
        }
    }

    private void validateChainConsistency(List<AtomRecord> atoms, List<ValidationWarning> warnings) {
        Map<String, List<Integer>> chainRanges = new LinkedHashMap<>();
        for (AtomRecord atom : atoms) {
            chainRanges.computeIfAbsent(atom.getChainId(), k -> new ArrayList<>());
            List<Integer> range = chainRanges.get(atom.getChainId());
            if (range.isEmpty()) {
                range.add(atom.getLineNumber());
                range.add(atom.getLineNumber());
            } else {
                range.set(1, atom.getLineNumber());
            }
        }

        for (Map.Entry<String, List<Integer>> entry : chainRanges.entrySet()) {
            String chain = entry.getKey();
            int start = entry.getValue().get(0);
            int end = entry.getValue().get(1);

            for (AtomRecord atom : atoms) {
                if (!atom.getChainId().equals(chain)) {
                    if (atom.getLineNumber() > start && atom.getLineNumber() < end) {
                        warnings.add(new ValidationWarning(atom.getLineNumber(), "chainId",
                                "Chain " + chain + " is not contiguous; atom " + atom.getSerialNumber() +
                                        " from chain " + atom.getChainId() + " found within its range", "WARNING"));
                        break;
                    }
                }
            }
        }
    }

    private String extractElementFromName(String atomName) {
        String trimmed = atomName.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            if (first == ' ' || Character.isDigit(first)) {
                char second = trimmed.charAt(1);
                String oneLetter = String.valueOf(second).toUpperCase();
                if (KNOWN_ELEMENTS.contains(oneLetter)) return oneLetter;
            }
        }
        String firstChar = trimmed.substring(0, 1).toUpperCase();
        if (KNOWN_ELEMENTS.contains(firstChar)) return firstChar;
        if (trimmed.length() >= 2) {
            String twoLetter = trimmed.substring(0, 2).toUpperCase();
            if (KNOWN_ELEMENTS.contains(twoLetter)) return twoLetter;
        }
        return "C";
    }
}
