package com.meteorology.nwp.io;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.*;
import ucar.nc2.*;
import ucar.nc2.write.NetcdfFormatWriter;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public class NetCDFHandler {
    private static final Logger logger = LoggerFactory.getLogger(NetCDFHandler.class);
    private final NWPConfig config;
    private final GridDefinition grid;
    private final int compressionLevel;

    public NetCDFHandler(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.grid = grid;
        this.compressionLevel = config.getIOConfig().getConfig("netcdf").getInt("compression-level");
    }

    public ModelState readInitialConditions(String filePath) throws IOException {
        logger.info("Reading NetCDF initial conditions: {}", filePath);
        ModelState state = new ModelState(grid);
        state.ensurePrognosticFields();

        try (NetcdfFile ncFile = NetcdfFiles.open(filePath)) {
            readGlobalAttributes(ncFile, state);
            readDimensions(ncFile);
            for (VariableType type : VariableType.values()) {
                Variable ncVar = ncFile.findVariable(type.name().toLowerCase());
                if (ncVar == null) ncVar = ncFile.findVariable(type.name());
                if (ncVar != null) {
                    state.addField(type);
                    readVariable(ncVar, state.getField(type));
                }
            }
        }

        return state;
    }

    private void readGlobalAttributes(NetcdfFile ncFile, ModelState state) {
        Attribute initTimeAttr = ncFile.findGlobalAttribute("initialization_time");
        if (initTimeAttr != null) {
            String val = initTimeAttr.getStringValue();
            if (val != null) {
                state.setInitializationTime(Instant.parse(val));
                state.setCurrentTime(Instant.parse(val));
            }
        }
        Attribute forecastAttr = ncFile.findGlobalAttribute("forecast_step");
        if (forecastAttr != null) {
            state.setForecastStep(forecastAttr.getNumericValue().intValue());
        }
        Attribute simTimeAttr = ncFile.findGlobalAttribute("simulation_time");
        if (simTimeAttr != null) {
            state.setSimulationTime(simTimeAttr.getNumericValue().doubleValue());
        }
        Attribute versionAttr = ncFile.findGlobalAttribute("version_tag");
        if (versionAttr != null) {
            state.setVersionTag(versionAttr.getStringValue());
        }
    }

    private void readDimensions(NetcdfFile ncFile) {
        Dimension xDim = ncFile.findDimension("x");
        Dimension yDim = ncFile.findDimension("y");
        Dimension zDim = ncFile.findDimension("z");
        if (xDim != null && xDim.getLength() != grid.getNX()) {
            logger.warn("Grid X mismatch: file={}, model={}", xDim.getLength(), grid.getNX());
        }
        if (yDim != null && yDim.getLength() != grid.getNY()) {
            logger.warn("Grid Y mismatch: file={}, model={}", yDim.getLength(), grid.getNY());
        }
    }

    private void readVariable(Variable ncVar, DataField field) throws IOException {
        try {
            Array data = ncVar.read();
            int[] shape = data.getShape();
            IndexIterator iter = data.getIndexIterator();

            if (shape.length == 3) {
                int fz = Math.min(shape[0], field.getNZ());
                int fy = Math.min(shape[1], field.getNY());
                int fx = Math.min(shape[2], field.getNX());
                for (int k = 0; k < fz; k++) {
                    for (int j = 0; j < fy; j++) {
                        for (int i = 0; i < fx; i++) {
                            if (iter.hasNext()) {
                                field.set(i, j, k, iter.getDoubleNext());
                            }
                        }
                    }
                }
            } else if (shape.length == 2) {
                int fy = Math.min(shape[0], field.getNY());
                int fx = Math.min(shape[1], field.getNX());
                for (int j = 0; j < fy; j++) {
                    for (int i = 0; i < fx; i++) {
                        if (iter.hasNext()) {
                            field.set(i, j, iter.getDoubleNext());
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read variable {}: {}", ncVar.getShortName(), e.getMessage());
            throw e;
        }
    }

    public void writeForecast(ModelState state, String filePath, List<String> outputVars) throws IOException {
        logger.info("Writing NetCDF forecast: {}", filePath);
        File outFile = new File(filePath);
        File parentDir = outFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        NetcdfFormatWriter.Builder writerBuilder = NetcdfFormatWriter.createNewNetcdf4(
                NetcdfFileFormat.NETCDF4, filePath, null);

        writerBuilder.addDimension("x", grid.getNX());
        writerBuilder.addDimension("y", grid.getNY());
        writerBuilder.addDimension("z", grid.getNZ());

        addCoordinateVariables(writerBuilder);
        addGlobalAttributes(writerBuilder, state);

        for (String varName : outputVars) {
            VariableType type;
            try {
                type = VariableType.valueOf(varName);
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown output variable: {}", varName);
                continue;
            }
            DataField field = state.getField(type);
            if (field == null) continue;
            addOutputVariable(writerBuilder, type, field);
        }

        try (NetcdfFormatWriter writer = writerBuilder.build()) {
            writeCoordinateVariables(writer);
            for (String varName : outputVars) {
                VariableType type;
                try {
                    type = VariableType.valueOf(varName);
                } catch (IllegalArgumentException e) { continue; }
                DataField field = state.getField(type);
                if (field == null) continue;
                writeVariable(writer, type, field);
            }
        }

        logger.info("Forecast written successfully: {}", filePath);
    }

    private void addCoordinateVariables(NetcdfFormatWriter.Builder builder) {
        Variable.Builder<?> lonVar = Variable.builder().setName("lon")
                .setDataType(DataType.DOUBLE).addDimensions("x");
        lonVar.addAttribute(new Attribute("units", "degrees_east"));
        lonVar.addAttribute(new Attribute("long_name", "longitude"));
        builder.addVariable(lonVar);

        Variable.Builder<?> latVar = Variable.builder().setName("lat")
                .setDataType(DataType.DOUBLE).addDimensions("y");
        latVar.addAttribute(new Attribute("units", "degrees_north"));
        latVar.addAttribute(new Attribute("long_name", "latitude"));
        builder.addVariable(latVar);

        Variable.Builder<?> levelVar = Variable.builder().setName("level")
                .setDataType(DataType.DOUBLE).addDimensions("z");
        levelVar.addAttribute(new Attribute("units", "sigma"));
        levelVar.addAttribute(new Attribute("long_name", "sigma_level"));
        builder.addVariable(levelVar);
    }

    private void addGlobalAttributes(NetcdfFormatWriter.Builder builder, ModelState state) {
        builder.addAttribute(new Attribute("title", "NWP Model Forecast Output"));
        builder.addAttribute(new Attribute("institution", "Meteorological Bureau"));
        builder.addAttribute(new Attribute("source", "NWP Java Solver v1.0"));
        if (state.getInitializationTime() != null) {
            builder.addAttribute(new Attribute("initialization_time", state.getInitializationTime().toString()));
        }
        if (state.getCurrentTime() != null) {
            builder.addAttribute(new Attribute("valid_time", state.getCurrentTime().toString()));
        }
        builder.addAttribute(new Attribute("forecast_step", state.getForecastStep()));
        builder.addAttribute(new Attribute("simulation_time", state.getSimulationTime()));
        builder.addAttribute(new Attribute("Conventions", "CF-1.8"));
        if (state.getVersionTag() != null) {
            builder.addAttribute(new Attribute("version_tag", state.getVersionTag()));
        }
        if (state.getExperimentId() != null) {
            builder.addAttribute(new Attribute("experiment_id", state.getExperimentId()));
        }
    }

    private void addOutputVariable(NetcdfFormatWriter.Builder builder, VariableType type, DataField field) {
        Variable.Builder<?> varBuilder = Variable.builder()
                .setName(type.name())
                .setDataType(DataType.FLOAT);

        if (type.is3D()) {
            varBuilder.addDimensions("z", "y", "x");
        } else {
            varBuilder.addDimensions("y", "x");
        }

        varBuilder.addAttribute(new Attribute("units", type.getUnit()));
        varBuilder.addAttribute(new Attribute("long_name", type.getDescription()));
        varBuilder.addAttribute(new Attribute("_FillValue", -9999.0f));

        builder.addVariable(varBuilder);
    }

    private void writeCoordinateVariables(NetcdfFormatWriter writer) throws IOException, InvalidRangeException {
        ArrayDouble lonData = new ArrayDouble.D1(grid.getNX());
        for (int i = 0; i < grid.getNX(); i++) lonData.set(i, grid.getLon(i));
        writer.write("lon", lonData);

        ArrayDouble latData = new ArrayDouble.D1(grid.getNY());
        for (int j = 0; j < grid.getNY(); j++) latData.set(j, grid.getLat(j));
        writer.write("lat", latData);

        ArrayDouble levelData = new ArrayDouble.D1(grid.getNZ());
        for (int k = 0; k < grid.getNZ(); k++) levelData.set(k, grid.getSigmaLevel(k));
        writer.write("level", levelData);
    }

    private void writeVariable(NetcdfFormatWriter writer, VariableType type, DataField field)
            throws IOException, InvalidRangeException {
        Array data;
        if (type.is3D()) {
            ArrayFloat.D3 data3d = new ArrayFloat.D3(field.getNZ(), field.getNY(), field.getNX());
            for (int k = 0; k < field.getNZ(); k++) {
                for (int j = 0; j < field.getNY(); j++) {
                    for (int i = 0; i < field.getNX(); i++) {
                        data3d.set(k, j, i, (float) field.get(i, j, k));
                    }
                }
            }
            data = data3d;
        } else {
            ArrayFloat.D2 data2d = new ArrayFloat.D2(field.getNY(), field.getNX());
            for (int j = 0; j < field.getNY(); j++) {
                for (int i = 0; i < field.getNX(); i++) {
                    data2d.set(j, i, (float) field.get(i, j));
                }
            }
            data = data2d;
        }
        writer.write(type.name(), data);
    }
}
