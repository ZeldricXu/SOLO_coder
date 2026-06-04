package com.proteinviewer.surface;

import com.proteinviewer.domain.Atom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ElectrostaticGrid {
    private static final Logger logger = LoggerFactory.getLogger(ElectrostaticGrid.class);
    private static final double EPSILON = 1e-6;
    private static final double NUCLEUS_THRESHOLD_FACTOR = 0.2;

    private final int gridSize;
    private final double[] origin;
    private final double[] spacing;
    private final double[][][] density;
    private final double[][][] potential;

    public ElectrostaticGrid(int gridSize, double[] origin, double[] spacing,
                             double[][][] density, double[][][] potential) {
        this.gridSize = gridSize;
        this.origin = origin;
        this.spacing = spacing;
        this.density = density;
        this.potential = potential;
    }

    public int getGridSize() { return gridSize; }
    public double[] getOrigin() { return origin; }
    public double[] getSpacing() { return spacing; }
    public double[][][] getDensity() { return density; }
    public double[][][] getPotential() { return potential; }

    public static ElectrostaticGrid compute(List<Atom> atoms, int gridSize, double padding) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE, maxZ = Double.MIN_VALUE;
        for (Atom atom : atoms) {
            minX = Math.min(minX, atom.getX()); minY = Math.min(minY, atom.getY()); minZ = Math.min(minZ, atom.getZ());
            maxX = Math.max(maxX, atom.getX()); maxY = Math.max(maxY, atom.getY()); maxZ = Math.max(maxZ, atom.getZ());
        }
        minX -= padding; minY -= padding; minZ -= padding;
        maxX += padding; maxY += padding; maxZ += padding;

        double dx = (maxX - minX) / (gridSize - 1);
        double dy = (maxY - minY) / (gridSize - 1);
        double dz = (maxZ - minZ) / (gridSize - 1);

        double[][][] density = new double[gridSize][gridSize][gridSize];
        double[][][] potential = new double[gridSize][gridSize][gridSize];
        boolean[][][] needsInterpolation = new boolean[gridSize][gridSize][gridSize];
        double probeRadius = 1.4;

        for (Atom atom : atoms) {
            double atomicRadius = getAtomicRadius(atom.getElement());
            double r = atomicRadius + probeRadius;
            double r2 = r * r;
            double nucleusThreshold = atomicRadius * NUCLEUS_THRESHOLD_FACTOR;
            double nucleusThreshold2 = nucleusThreshold * nucleusThreshold;
            int ixMin = Math.max(0, (int) ((atom.getX() - r - minX) / dx));
            int ixMax = Math.min(gridSize - 1, (int) ((atom.getX() + r - minX) / dx));
            int iyMin = Math.max(0, (int) ((atom.getY() - r - minY) / dy));
            int iyMax = Math.min(gridSize - 1, (int) ((atom.getY() + r - minY) / dy));
            int izMin = Math.max(0, (int) ((atom.getZ() - r - minZ) / dz));
            int izMax = Math.min(gridSize - 1, (int) ((atom.getZ() + r - minZ) / dz));

            double charge = getPartialCharge(atom);
            for (int ix = ixMin; ix <= ixMax; ix++) {
                double px = minX + ix * dx;
                for (int iy = iyMin; iy <= iyMax; iy++) {
                    double py = minY + iy * dy;
                    for (int iz = izMin; iz <= izMax; iz++) {
                        double pz = minZ + iz * dz;
                        double dist2 = (px - atom.getX()) * (px - atom.getX()) +
                                (py - atom.getY()) * (py - atom.getY()) +
                                (pz - atom.getZ()) * (pz - atom.getZ());
                        if (dist2 < r2) {
                            density[ix][iy][iz] = 1.0;
                        }
                        if (dist2 < nucleusThreshold2) {
                            needsInterpolation[ix][iy][iz] = true;
                        }
                        double dist = Math.sqrt(dist2);
                        dist = Math.max(dist, EPSILON);
                        potential[ix][iy][iz] += charge / dist;
                    }
                }
            }
        }

        interpolateNucleusPoints(potential, needsInterpolation, gridSize);
        validateAndFixPotentials(potential, gridSize);

        return new ElectrostaticGrid(gridSize,
                new double[]{minX, minY, minZ},
                new double[]{dx, dy, dz},
                density, potential);
    }

    private static void interpolateNucleusPoints(double[][][] potential, boolean[][][] needsInterpolation, int gridSize) {
        double[][][] original = new double[gridSize][gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                System.arraycopy(potential[i][j], 0, original[i][j], 0, gridSize);
            }
        }

        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0},
                {0, 0, 1}, {0, 0, -1}
        };

        for (int ix = 0; ix < gridSize; ix++) {
            for (int iy = 0; iy < gridSize; iy++) {
                for (int iz = 0; iz < gridSize; iz++) {
                    if (needsInterpolation[ix][iy][iz]) {
                        double sum = 0.0;
                        int count = 0;

                        for (int[] dir : directions) {
                            int nx = ix + dir[0];
                            int ny = iy + dir[1];
                            int nz = iz + dir[2];

                            if (nx >= 0 && nx < gridSize &&
                                ny >= 0 && ny < gridSize &&
                                nz >= 0 && nz < gridSize &&
                                !needsInterpolation[nx][ny][nz]) {
                                sum += original[nx][ny][nz];
                                count++;
                            }
                        }

                        if (count >= 2) {
                            potential[ix][iy][iz] = sum / count;
                        } else {
                            potential[ix][iy][iz] = interpolateLargerStencil(original, needsInterpolation, ix, iy, iz, gridSize);
                        }
                    }
                }
            }
        }
    }

    private static double interpolateLargerStencil(double[][][] original, boolean[][][] needsInterpolation,
                                                    int x, int y, int z, int gridSize) {
        double sum = 0.0;
        int count = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int nx = x + dx;
                    int ny = y + dy;
                    int nz = z + dz;

                    if (nx >= 0 && nx < gridSize &&
                        ny >= 0 && ny < gridSize &&
                        nz >= 0 && nz < gridSize &&
                        !needsInterpolation[nx][ny][nz]) {
                        sum += original[nx][ny][nz];
                        count++;
                    }
                }
            }
        }

        if (count > 0) {
            return sum / count;
        }
        return 0.0;
    }

    private static void validateAndFixPotentials(double[][][] potential, int gridSize) {
        int nanCount = 0;
        int infCount = 0;

        for (int ix = 0; ix < gridSize; ix++) {
            for (int iy = 0; iy < gridSize; iy++) {
                for (int iz = 0; iz < gridSize; iz++) {
                    double val = potential[ix][iy][iz];
                    if (Double.isNaN(val)) {
                        nanCount++;
                        potential[ix][iy][iz] = interpolateNeighbors(potential, ix, iy, iz, gridSize);
                    } else if (Double.isInfinite(val)) {
                        infCount++;
                        potential[ix][iy][iz] = interpolateNeighbors(potential, ix, iy, iz, gridSize);
                    }
                }
            }
        }

        if (nanCount > 0 || infCount > 0) {
            logger.warn("Found and fixed {} NaN and {} Infinity values in electrostatic potential grid", nanCount, infCount);
        }
    }

    private static double interpolateNeighbors(double[][][] potential, int x, int y, int z, int gridSize) {
        double sum = 0.0;
        int count = 0;

        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0},
                {0, 0, 1}, {0, 0, -1}
        };

        for (int[] dir : directions) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];

            if (nx >= 0 && nx < gridSize &&
                ny >= 0 && ny < gridSize &&
                nz >= 0 && nz < gridSize) {
                double val = potential[nx][ny][nz];
                if (!Double.isNaN(val) && !Double.isInfinite(val)) {
                    sum += val;
                    count++;
                }
            }
        }

        if (count > 0) {
            return sum / count;
        }
        return 0.0;
    }

    public static double getAtomicRadius(String element) {
        return switch (element.toUpperCase()) {
            case "H" -> 1.2;
            case "C" -> 1.7;
            case "N" -> 1.55;
            case "O" -> 1.52;
            case "S" -> 1.8;
            case "P" -> 1.8;
            case "FE" -> 2.0;
            case "ZN" -> 1.39;
            case "CU" -> 1.4;
            case "MG" -> 1.73;
            case "MN" -> 1.39;
            case "CA" -> 2.31;
            default -> 1.7;
        };
    }

    public static double getPartialCharge(Atom atom) {
        String name = atom.getAtomName();
        String res = atom.getResidueName();
        if (name.equals("NZ") && res.equals("LYS")) return 1.0;
        if ((name.equals("NH1") || name.equals("NH2")) && res.equals("ARG")) return 0.5;
        if ((name.equals("OD1") || name.equals("OD2")) && res.equals("ASP")) return -0.5;
        if ((name.equals("OE1") || name.equals("OE2")) && res.equals("GLU")) return -0.5;
        if (name.equals("O") && !atom.isHetatm()) return -0.5;
        if (name.equals("N") && !atom.isHetatm()) return 0.3;
        return 0.0;
    }
}
