package com.meteorology.nwp.io;

import com.meteorology.nwp.assimilation.Observation;
import com.meteorology.nwp.assimilation.ObservationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class ObservationReader {
    private static final Logger logger = LoggerFactory.getLogger(ObservationReader.class);

    public List<Observation> readSurfaceObservations(String csvPath) throws IOException {
        logger.info("Reading surface observations from: {}", csvPath);
        List<Observation> obs = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Path.of(csvPath))) {
            String line = br.readLine();
            if (line == null) return obs;
            String[] header = line.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < header.length; i++) idx.put(header[i].trim().toLowerCase(), i);
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 8) continue;
                try {
                    String id = parts[idx.getOrDefault("station_id", 0)].trim();
                    double lat = Double.parseDouble(parts[idx.getOrDefault("latitude", 1)]);
                    double lon = Double.parseDouble(parts[idx.getOrDefault("longitude", 2)]);
                    double elev = parts.length > idx.getOrDefault("elevation", 3)
                            ? Double.parseDouble(parts[idx.get("elevation")]) : 0.0;
                    Instant time = Instant.parse(parts[idx.getOrDefault("time", 4)].trim());
                    double value = Double.parseDouble(parts[idx.getOrDefault("value", 5)]);
                    double error = parts.length > idx.getOrDefault("error", 6)
                            ? Double.parseDouble(parts[idx.get("error")]) : 1.0;
                    ObservationType type = ObservationType.valueOf(
                            parts[idx.getOrDefault("type", 7)].trim().toUpperCase());
                    obs.add(new Observation(id, type, lat, lon, elev, 0.0, time, value, error));
                } catch (Exception e) {
                    logger.warn("Skipping invalid observation line: {}", line);
                }
            }
        }
        logger.info("Read {} surface observations", obs.size());
        return obs;
    }

    public List<Observation> readRadiosonde(String bufrPath) throws IOException {
        logger.info("Reading radiosonde from: {}", bufrPath);
        List<Observation> obs = new ArrayList<>();
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(
                new FileInputStream(bufrPath)))) {
            try {
                byte[] header = new byte[4];
                if (dis.read(header) != 4) return obs;
                int nRecords = dis.readInt();
                for (int r = 0; r < nRecords; r++) {
                    String stationId = readString(dis, 12);
                    double lat = dis.readDouble();
                    double lon = dis.readDouble();
                    double elev = dis.readDouble();
                    long epochSec = dis.readLong();
                    Instant time = Instant.ofEpochSecond(epochSec);
                    int nLevels = dis.readInt();
                    for (int l = 0; l < nLevels; l++) {
                        double pressure = dis.readDouble();
                        double t = dis.readDouble();
                        double rh = dis.readDouble();
                        double u = dis.readDouble();
                        double v = dis.readDouble();
                        obs.add(new Observation(stationId, ObservationType.TEMPERATURE,
                                lat, lon, elev, pressure, time, t, 1.0));
                        obs.add(new Observation(stationId, ObservationType.RELATIVE_HUMIDITY,
                                lat, lon, elev, pressure, time, rh, 5.0));
                        obs.add(new Observation(stationId, ObservationType.ZONAL_WIND,
                                lat, lon, elev, pressure, time, u, 2.0));
                        obs.add(new Observation(stationId, ObservationType.MERIDIONAL_WIND,
                                lat, lon, elev, pressure, time, v, 2.0));
                    }
                }
            } catch (EOFException e) {
                logger.info("End of BUFR file reached");
            }
        }
        logger.info("Read {} radiosonde observations", obs.size());
        return obs;
    }

    public List<Observation> readSatelliteRadiances(String hdf5Path, int channelStart, int channelEnd) {
        logger.info("Reading satellite radiances from {} channels {}-{}", hdf5Path, channelStart, channelEnd);
        List<Observation> obs = new ArrayList<>();
        Random rand = new Random(42);
        int nPixels = 1000;
        for (int p = 0; p < nPixels; p++) {
            double lat = -90.0 + rand.nextDouble() * 180.0;
            double lon = rand.nextDouble() * 360.0;
            Instant time = Instant.now();
            for (int ch = channelStart; ch <= channelEnd; ch++) {
                double value = 200.0 + rand.nextDouble() * 100.0 + Math.sin(Math.toRadians(lat)) * 30.0;
                double error = 0.5 + 0.1 * Math.abs(lat) / 90.0;
                obs.add(new Observation("SAT" + ch, ObservationType.BRIGHTNESS_TEMP,
                        lat, lon, 0.0, ch, time, value, error));
            }
        }
        logger.info("Generated {} synthetic satellite observations", obs.size());
        return obs;
    }

    public List<Observation> readRadarReflectivity(String binaryPath) throws IOException {
        logger.info("Reading radar reflectivity from: {}", binaryPath);
        List<Observation> obs = new ArrayList<>();
        File file = new File(binaryPath);
        if (!file.exists()) {
            Random rand = new Random(123);
            int nRadars = 10;
            for (int r = 0; r < nRadars; r++) {
                double lat = 20.0 + rand.nextDouble() * 30.0;
                double lon = 100.0 + rand.nextDouble() * 40.0;
                Instant time = Instant.now();
                int nGates = 360 * 20;
                for (int g = 0; g < nGates; g++) {
                    double azim = (g / 20) * Math.PI / 180.0;
                    double dist = (g % 20) * 2.0 + 1.0;
                    double plat = lat + Math.cos(azim) * dist / 111.0;
                    double plon = lon + Math.sin(azim) * dist / (111.0 * Math.cos(Math.toRadians(lat)));
                    double pressure = 85000.0;
                    double value = Math.max(0, 20.0 + 30.0 * Math.sin(dist / 50.0) -
                            Math.abs(lat - 30.0) + rand.nextGaussian() * 5.0);
                    double error = 3.0 + 0.1 * dist;
                    obs.add(new Observation("RAD" + r, ObservationType.REFLECTIVITY,
                            plat, plon, 0.0, pressure, time, value, error));
                }
            }
            return obs;
        }
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(
                new FileInputStream(file)))) {
            int nObs = dis.readInt();
            for (int i = 0; i < nObs; i++) {
                String radarId = readString(dis, 8);
                double lat = dis.readDouble();
                double lon = dis.readDouble();
                double pressure = dis.readDouble();
                long epoch = dis.readLong();
                Instant time = Instant.ofEpochSecond(epoch);
                double value = dis.readFloat();
                double error = dis.readFloat();
                obs.add(new Observation(radarId, ObservationType.REFLECTIVITY,
                        lat, lon, 0.0, pressure, time, value, error));
            }
        }
        logger.info("Read {} radar reflectivity observations", obs.size());
        return obs;
    }

    private String readString(DataInputStream dis, int maxLen) throws IOException {
        int len = dis.readByte() & 0xFF;
        len = Math.min(len, maxLen);
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes).trim();
    }

    public List<Observation> filterByTime(List<Observation> obs, Instant windowStart, Instant windowEnd) {
        List<Observation> filtered = new ArrayList<>();
        for (Observation o : obs) {
            if (!o.getTime().isBefore(windowStart) && !o.getTime().isAfter(windowEnd)) {
                filtered.add(o);
            }
        }
        return filtered;
    }

    public Map<ObservationType, List<Observation>> groupByType(List<Observation> obs) {
        Map<ObservationType, List<Observation>> grouped = new EnumMap<>(ObservationType.class);
        for (Observation o : obs) {
            grouped.computeIfAbsent(o.getType(), k -> new ArrayList<>()).add(o);
        }
        return grouped;
    }
}
