package com.meteorology.nwp.parallel;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class GridPartitioner implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(GridPartitioner.class);

    public static class Partition implements Serializable {
        public final int pid;
        public final int iStart, iEnd, jStart, jEnd;
        public final int localNx, localNy;
        public final int haloWidth;
        public final double subdomainLonMin, subdomainLonMax;
        public final double subdomainLatMin, subdomainLatMax;
        public final List<Integer> neighbors;

        public Partition(int pid, int iS, int iE, int jS, int jE,
                         int halo, GridDefinition grid) {
            this.pid = pid;
            this.iStart = iS; this.iEnd = iE;
            this.jStart = jS; this.jEnd = jE;
            this.localNx = iE - iS + 2 * halo;
            this.localNy = jE - jS + 2 * halo;
            this.haloWidth = halo;
            this.subdomainLonMin = grid.lonMin + iS * grid.dLon;
            this.subdomainLonMax = grid.lonMin + iE * grid.dLon;
            this.subdomainLatMin = grid.latMin + jS * grid.dLat;
            this.subdomainLatMax = grid.latMin + jE * grid.dLat;
            this.neighbors = new ArrayList<>();
        }

        public boolean containsGlobalPoint(int i, int j) {
            return i >= iStart && i < iEnd && j >= jStart && j < jEnd;
        }

        public int toLocalI(int globalI) { return globalI - iStart + haloWidth; }
        public int toLocalJ(int globalJ) { return globalJ - jStart + haloWidth; }

        public int toGlobalI(int localI) { return localI - haloWidth + iStart; }
        public int toGlobalJ(int localJ) { return localJ - haloWidth + jStart; }

        @Override
        public String toString() {
            return String.format("P%d[i:%d-%d,j:%d-%d,size:%dx%d]",
                    pid, iStart, iEnd, jStart, jEnd, localNx - 2*haloWidth, localNy - 2*haloWidth);
        }
    }

    private final NWPConfig config;
    private final int nx, ny;
    private final int haloWidth;
    private final int numPartitionsX;
    private final int numPartitionsY;
    private final Partition[] partitions;
    private final int totalPartitions;

    public GridPartitioner(NWPConfig config) {
        this.config = config;
        this.nx = config.getNX();
        this.ny = config.getNY();
        this.haloWidth = config.getInt("nwp.parallel.haloWidth", 3);
        this.numPartitionsX = config.getInt("nwp.parallel.numPartitionsX", 4);
        this.numPartitionsY = config.getInt("nwp.parallel.numPartitionsY", 4);
        this.totalPartitions = numPartitionsX * numPartitionsY;
        this.partitions = new Partition[totalPartitions];
        createPartitions();
        setupNeighbors();
        validatePartitions();
    }

    private void createPartitions() {
        GridDefinition grid = config.getGrid();
        int[] xBounds = computeChunkBounds(nx, numPartitionsX);
        int[] yBounds = computeChunkBounds(ny, numPartitionsY);
        for (int py = 0; py < numPartitionsY; py++) {
            for (int px = 0; px < numPartitionsX; px++) {
                int pid = py * numPartitionsX + px;
                int iS = xBounds[px], iE = xBounds[px + 1];
                int jS = yBounds[py], jE = yBounds[py + 1];
                partitions[pid] = new Partition(pid, iS, iE, jS, jE, haloWidth, grid);
            }
        }
        logger.info("分区: {}×{}={} 总分区, halo={}",
                numPartitionsX, numPartitionsY, totalPartitions, haloWidth);
    }

    private int[] computeChunkBounds(int total, int numChunks) {
        int[] bounds = new int[numChunks + 1];
        int base = total / numChunks;
        int remainder = total % numChunks;
        bounds[0] = 0;
        for (int c = 0; c < numChunks; c++) {
            bounds[c + 1] = bounds[c] + base + (c < remainder ? 1 : 0);
        }
        return bounds;
    }

    private void setupNeighbors() {
        for (int py = 0; py < numPartitionsY; py++) {
            for (int px = 0; px < numPartitionsX; px++) {
                int pid = py * numPartitionsX + px;
                Partition p = partitions[pid];
                for (int dY = -1; dY <= 1; dY++) {
                    for (int dX = -1; dX <= 1; dX++) {
                        if (dX == 0 && dY == 0) continue;
                        int npx = (px + dX + numPartitionsX) % numPartitionsX;
                        int npy = py + dY;
                        if (npy < 0 || npy >= numPartitionsY) continue;
                        int npid = npy * numPartitionsX + npx;
                        p.neighbors.add(npid);
                    }
                }
            }
        }
        for (Partition p : partitions) {
            logger.debug("分区{}邻居: {}", p.pid, p.neighbors);
        }
    }

    private void validatePartitions() {
        int countI = 0, countJ = 0;
        for (int px = 0; px < numPartitionsX; px++) {
            countI += partitions[px].iEnd - partitions[px].iStart;
        }
        for (int py = 0; py < numPartitionsY; py++) {
            countJ += partitions[py * numPartitionsX].jEnd - partitions[py * numPartitionsX].jStart;
        }
        if (countI != nx || countJ != ny) {
            throw new IllegalStateException(String.format(
                    "分区错误: 总网格I=%d(预期%d) J=%d(预期%d)", countI, nx, countJ, ny));
        }
        logger.info("分区验证通过: 总I={} J={}", countI, countJ);
    }

    public Partition getPartition(int pid) { return partitions[pid]; }

    public Partition findPartition(int i, int j) {
        for (Partition p : partitions) {
            if (p.containsGlobalPoint(i, j)) return p;
        }
        return null;
    }

    public Partition[] getAllPartitions() { return partitions; }

    public int getTotalPartitions() { return totalPartitions; }
    public int getNumPartitionsX() { return numPartitionsX; }
    public int getNumPartitionsY() { return numPartitionsY; }
    public int getHaloWidth() { return haloWidth; }

    public void extractSubdomain(ModelState globalState, Partition p, ModelState localState) {
        GridDefinition grid = config.getGrid();
        int h = haloWidth;
        for (VariableType var : VariableType.values()) {
            DataField gf = globalState.fields.get(var);
            DataField lf = localState.fields.get(var);
            if (gf == null || lf == null) continue;
            boolean is3D = gf.getNDim() == 3;
            int nz = config.getNZ();
            int gnx = nx, gny = ny;
            int lnx = p.localNx, lny = p.localNy;
            int kMax = is3D ? nz : 1;
            for (int k = 0; k < kMax; k++) {
                for (int lj = 0; lj < lny; lj++) {
                    int gj = p.toGlobalJ(lj);
                    int gjWrapped = (gj + gny) % gny;
                    boolean polarCopy = (gj < 0 || gj >= gny);
                    for (int li = 0; li < lnx; li++) {
                        int gi = p.toGlobalI(li);
                        int giWrapped = ((gi % gnx) + gnx) % gnx;
                        double val;
                        if (polarCopy) {
                            int gjPolar = (gj < 0) ? -gj : 2 * (gny - 1) - gj;
                            int giPolar = (giWrapped + gnx / 2) % gnx;
                            val = is3D ? gf.get(giPolar + gnx * (gjPolar + gny * k))
                                       : gf.get(giPolar + gnx * gjPolar);
                        } else {
                            val = is3D ? gf.get(giWrapped + gnx * (gjWrapped + gny * k))
                                       : gf.get(giWrapped + gnx * gjWrapped);
                        }
                        int lIdx = is3D ? (li + lnx * (lj + lny * k)) : (li + lnx * lj);
                        lf.set(lIdx, val);
                    }
                }
            }
        }
    }

    public void mergeSubdomain(ModelState localState, Partition p, ModelState globalState) {
        int h = haloWidth;
        for (VariableType var : VariableType.values()) {
            DataField lf = localState.fields.get(var);
            DataField gf = globalState.fields.get(var);
            if (lf == null || gf == null) continue;
            boolean is3D = lf.getNDim() == 3;
            int nz = config.getNZ();
            int gnx = nx;
            int lnx = p.localNx, lny = p.localNy;
            int kMax = is3D ? nz : 1;
            for (int k = 0; k < kMax; k++) {
                for (int lj = h; lj < lny - h; lj++) {
                    int gj = p.toGlobalJ(lj);
                    for (int li = h; li < lnx - h; li++) {
                        int gi = p.toGlobalI(li);
                        double v = is3D ? lf.get(li + lnx * (lj + lny * k)) : lf.get(li + lnx * lj);
                        if (is3D) gf.set(gi + gnx * (gj + ny * k), v);
                        else gf.set(gi + gnx * gj, v);
                    }
                }
            }
        }
    }

    public ModelState createLocalState(Partition p) {
        return new ModelState(config, p.localNx, p.localNy, config.getNZ());
    }
}
