package com.meteorology.nwp.io;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.grib.grib2.Grib2Decode;
import ucar.grib.grib2.Grib2Input;
import ucar.grib.grib2.Grib2Record;
import ucar.grib.grib2.Grib2WriteIndex;
import ucar.nc2.time.CalendarDate;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class Grib2Codec {
    private static final Logger logger = LoggerFactory.getLogger(Grib2Codec.class);
    private final NWPConfig config;
    private final GridDefinition grid;

    public Grib2Codec(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.grid = grid;
    }

    public ModelState decode(String filePath) throws IOException {
        logger.info("Decoding GRIB2 file: {}", filePath);
        ModelState state = new ModelState(grid);
        state.ensurePrognosticFields();

        File file = new File(filePath);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            Grib2Input input = new Grib2Input(raf);
            input.scan(false, false);
            List<Grib2Record> records = input.getRecords();

            Map<VariableType, List<LevelRecord>> levelData = new EnumMap<>(VariableType.class);
            Instant initTime = null;

            for (Grib2Record record : records) {
                int discipline = record.getDiscipline();
                int category = record.getParameterCategory();
                int parameter = record.getParameterNumber();
                VariableType varType = VariableType.fromGribCode(discipline, category, parameter);
                if (varType == null) continue;

                int levelType = record.getLevelType1();
                double levelValue = record.getLevelValue1();

                if (initTime == null) {
                    CalendarDate cd = record.getReferenceTime();
                    initTime = Instant.ofEpochMilli(cd.getMillis());
                    state.setInitializationTime(initTime);
                    state.setCurrentTime(initTime);
                }

                try {
                    float[] data = Grib2Decode.getData(record, raf, -1);
                    levelData.computeIfAbsent(varType, k -> new ArrayList<>())
                            .add(new LevelRecord(levelType, levelValue, data));
                } catch (Exception e) {
                    logger.warn("Failed to decode record for {}: {}", varType, e.getMessage());
                }
            }

            remapToModelGrid(state, levelData);
        }

        return state;
    }

    private void remapToModelGrid(ModelState state, Map<VariableType, List<LevelRecord>> levelData) {
        for (Map.Entry<VariableType, List<LevelRecord>> entry : levelData.entrySet()) {
            VariableType type = entry.getKey();
            List<LevelRecord> records = entry.getValue();
            DataField field = state.getField(type);
            if (field == null) {
                state.addField(type);
                field = state.getField(type);
            }

            if (type.is3D()) {
                remap3D(field, records);
            } else {
                remap2D(field, records);
            }
        }
    }

    private void remap3D(DataField field, List<LevelRecord> records) {
        records.sort(Comparator.comparingDouble(r -> -r.levelValue));
        int nz = Math.min(field.getNZ(), records.size());
        for (int k = 0; k < nz; k++) {
            LevelRecord rec = records.get(k);
            float[] data = rec.data;
            int srcNx = (int) Math.sqrt(data.length);
            if (srcNx * srcNx != data.length) {
                srcNx = data.length / grid.getNY();
            }
            for (int j = 0; j < grid.getNY(); j++) {
                for (int i = 0; i < grid.getNX(); i++) {
                    int srcIdx = findSourceIndex(i, j, srcNx, data.length / srcNx);
                    if (srcIdx >= 0 && srcIdx < data.length) {
                        field.set(i, j, k, data[srcIdx]);
                    }
                }
            }
        }
    }

    private void remap2D(DataField field, List<LevelRecord> records) {
        if (records.isEmpty()) return;
        LevelRecord rec = records.get(0);
        float[] data = rec.data;
        int srcNx = (int) Math.sqrt(data.length);
        if (srcNx * srcNx != data.length) {
            srcNx = data.length / grid.getNY();
        }
        for (int j = 0; j < grid.getNY(); j++) {
            for (int i = 0; i < grid.getNX(); i++) {
                int srcIdx = findSourceIndex(i, j, srcNx, data.length / srcNx);
                if (srcIdx >= 0 && srcIdx < data.length) {
                    field.set(i, j, data[srcIdx]);
                }
            }
        }
    }

    private int findSourceIndex(int i, int j, int srcNx, int srcNy) {
        int si = (int) ((double) i / grid.getNX() * srcNx);
        int sj = (int) ((double) j / grid.getNY() * srcNy);
        si = Math.max(0, Math.min(srcNx - 1, si));
        sj = Math.max(0, Math.min(srcNy - 1, sj));
        return si + srcNx * sj;
    }

    public void encode(ModelState state, String filePath, List<String> variables) throws IOException {
        logger.info("Encoding GRIB2 file: {}", filePath);
        Files.createDirectories(Path.of(filePath).getParent());

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(filePath)))) {
            writeSection0(dos);
            writeSection1(dos, state);
            for (String varName : variables) {
                VariableType type = VariableType.valueOf(varName);
                DataField field = state.getField(type);
                if (field == null) continue;
                if (type.is3D()) {
                    double[] sigma = grid.getSigmaLevels();
                    for (int k = 0; k < field.getNZ(); k++) {
                        double pressure = sigma[k] * getMeanPSFC(state);
                        writeGribMessage(dos, state, type, 100, pressure, extractLevel(field, k));
                    }
                } else {
                    writeGribMessage(dos, state, type, 1, 0, extractLevel(field, 0));
                }
            }
            writeSection8(dos);
        }
    }

    private double getMeanPSFC(ModelState state) {
        DataField psfc = state.getField(VariableType.PSFC);
        return psfc != null ? psfc.mean() : 101325.0;
    }

    private float[] extractLevel(DataField field, int k) {
        int nx = field.getNX(), ny = field.getNY();
        float[] data = new float[nx * ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                data[i + nx * j] = (float) field.get(i, j, k);
            }
        }
        return data;
    }

    private void writeSection0(DataOutputStream dos) throws IOException {
        dos.writeBytes("GRIB");
        dos.writeShort(2);
        dos.writeLong(0);
    }

    private void writeSection1(DataOutputStream dos, ModelState state) throws IOException {
        dos.writeInt(21);
        dos.write(1);
        dos.writeShort(0);
        dos.write(1);
        dos.writeLong(System.currentTimeMillis());
    }

    private void writeGribMessage(DataOutputStream dos, ModelState state, VariableType type,
                                   int levelType, double levelValue, float[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream bdos = new DataOutputStream(baos);

        bdos.writeInt(72);
        bdos.write(0);
        bdos.writeShort(0);
        bdos.write(0);
        bdos.writeInt(grid.getNX());
        bdos.writeInt(grid.getNY());
        bdos.writeDouble(grid.getLatMin());
        bdos.writeDouble(grid.getLonMin());
        bdos.write(0);
        bdos.writeDouble(grid.getLatMax());
        bdos.writeDouble(grid.getLonMax());
        bdos.writeDouble(grid.getDX());
        bdos.writeDouble(grid.getDY());
        bdos.writeShort(0);

        bdos.writeInt(34);
        bdos.writeShort(0);
        bdos.write(levelType);
        bdos.writeDouble(levelValue);
        bdos.write(255);
        bdos.writeDouble(0);
        bdos.write(0);
        bdos.write(0);
        bdos.write(0);
        bdos.write(0);

        bdos.writeInt(9);
        bdos.write(0);
        bdos.write(0);
        bdos.write(0);

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : data) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        bdos.writeInt(21 + data.length * 4);
        bdos.write(0);
        bdos.write(0);
        bdos.write(1);
        bdos.write(4);
        bdos.writeFloat(min);
        bdos.writeFloat(max);
        for (float v : data) bdos.writeFloat(v);

        dos.write(baos.toByteArray());
    }

    private void writeSection8(DataOutputStream dos) throws IOException {
        dos.writeBytes("7777");
    }

    private static class LevelRecord {
        final int levelType;
        final double levelValue;
        final float[] data;

        LevelRecord(int levelType, double levelValue, float[] data) {
            this.levelType = levelType;
            this.levelValue = levelValue;
            this.data = data;
        }
    }
}
