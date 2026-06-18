package com.meteorology.nwp.parallel;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class HaloExchangeManager implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(HaloExchangeManager.class);
    private final NWPConfig config;
    private final GridPartitioner partitioner;
    private final int haloWidth;
    private final int nx, ny, nz;
    private final transient Map<Integer, CompletableFuture<double[][]>> sendBuffers;
    private final transient ExecutorService exchangeExecutor;
    private final transient ExecutorService computeExecutor;

    private final AtomicLong totalExchangeNanos = new AtomicLong(0);
    private final AtomicLong totalComputeNanos = new AtomicLong(0);
    private final AtomicLong totalOverlapNanos = new AtomicLong(0);
    private long exchangeCount = 0;

    public enum Direction {
        WEST(-1, 0), EAST(1, 0), SOUTH(0, -1), NORTH(0, 1),
        SOUTHWEST(-1, -1), SOUTHEAST(1, -1), NORTHWEST(-1, 1), NORTHEAST(1, 1);
        public final int di, dj;
        Direction(int di, int dj) { this.di = di; this.dj = dj; }
    }

    public HaloExchangeManager(NWPConfig config, GridPartitioner partitioner) {
        this.config = config;
        this.partitioner = partitioner;
        this.haloWidth = partitioner.getHaloWidth();
        this.nx = config.getNX();
        this.ny = config.getNY();
        this.nz = config.getNZ();
        this.sendBuffers = new ConcurrentHashMap<>();

        int nExchangeThreads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 4));
        this.exchangeExecutor = Executors.newFixedThreadPool(nExchangeThreads, r -> {
            Thread t = new Thread(r, "halo-exchange");
            t.setDaemon(true);
            return t;
        });

        int nComputeThreads = Math.min(
                Runtime.getRuntime().availableProcessors(),
                Math.max(2, Runtime.getRuntime().availableProcessors() - nExchangeThreads)
        );
        this.computeExecutor = Executors.newFixedThreadPool(nComputeThreads, r -> {
            Thread t = new Thread(r, "halo-compute");
            t.setDaemon(true);
            return t;
        });

        logger.info("HaloExchangeManager: {} exchange threads, {} compute threads, halo={}",
                nExchangeThreads, nComputeThreads, haloWidth);
    }

    public void performExchangeAsync(ModelState[] localStates, int step,
                                      Runnable interiorCompute) {
        if (localStates == null) return;
        long t0 = System.nanoTime();

        int totalP = partitioner.getTotalPartitions();
        if (localStates.length < totalP) {
            throw new IllegalArgumentException("localStates长度不足: " + localStates.length + " < " + totalP);
        }

        List<CompletableFuture<Void>> sendFutures = new ArrayList<>();
        for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
            sendFutures.add(CompletableFuture.runAsync(
                    () -> packAndSend(localStates, p), exchangeExecutor));
        }

        List<CompletableFuture<Void>> recvFutures = new ArrayList<>();
        for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
            recvFutures.add(CompletableFuture.runAsync(
                    () -> receiveAndUnpack(localStates, p), exchangeExecutor));
        }

        long computeStart = System.nanoTime();
        if (interiorCompute != null) {
            interiorCompute.run();
        }
        long computeEnd = System.nanoTime();
        long computeTime = computeEnd - computeStart;

        try {
            CompletableFuture.allOf(sendFutures.toArray(new CompletableFuture[0]))
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Halo发送超时 step={}: {}", step, e.getMessage());
        }

        try {
            CompletableFuture.allOf(recvFutures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Halo接收超时 step={}: {}", step, e.getMessage());
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                finalizePolarBoundaries(localStates[p.pid], p);
            }
        }

        long totalTime = System.nanoTime() - t0;
        long overlapTime = Math.max(0, computeTime - (totalTime - computeTime));

        totalExchangeNanos.addAndGet(totalTime);
        totalComputeNanos.addAndGet(computeTime);
        totalOverlapNanos.addAndGet(overlapTime);
        exchangeCount++;

        if (step % 100 == 0) {
            double totalMs = totalExchangeNanos.get() / 1e6;
            double computeMs = totalComputeNanos.get() / 1e6;
            double overlapMs = totalOverlapNanos.get() / 1e6;
            double overlapPct = totalMs > 0 ? 100.0 * overlapMs / totalMs : 0;
            logger.debug("Halo异步统计 step={}: 总耗时={:.1f}ms, 内部计算={:.1f}ms, 重叠={:.1f}ms ({:.1f}%)",
                    step, totalMs, computeMs, overlapMs, overlapPct);
        }
    }

    public void performExchange(ModelState[] localStates, int step) {
        performExchangeAsync(localStates, step, null);
    }

    public void performExchangeWithCompute(ModelState[] localStates, int step,
                                            Runnable interiorCompute) {
        performExchangeAsync(localStates, step, interiorCompute);
    }

    private void packAndSend(ModelState[] states, GridPartitioner.Partition p) {
        ModelState src = states[p.pid];
        if (src == null) return;
        Map<Direction, double[][]> packed = new EnumMap<>(Direction.class);
        for (Direction dir : Direction.values()) {
            GridPartitioner.Partition np = getNeighborPartition(p, dir);
            if (np == null) continue;
            double[][] buf = packBoundary(src, dir);
            packed.put(dir, buf);
            sendBuffers.put(np.pid * 16 + dir.ordinal(),
                    CompletableFuture.completedFuture(buf));
        }
    }

    private GridPartitioner.Partition getNeighborPartition(GridPartitioner.Partition p, Direction dir) {
        int px = p.pid % partitioner.getNumPartitionsX();
        int py = p.pid / partitioner.getNumPartitionsX();
        int npx = px + dir.di;
        int npy = py + dir.dj;
        if (npy < 0 || npy >= partitioner.getNumPartitionsY()) return null;
        npx = ((npx % partitioner.getNumPartitionsX()) + partitioner.getNumPartitionsX()) % partitioner.getNumPartitionsX();
        int npid = npy * partitioner.getNumPartitionsX() + npx;
        return partitioner.getPartition(npid);
    }

    private double[][] packBoundary(ModelState src, Direction dir) {
        List<VariableType> vars = getExchangedVariables();
        double[][] data = new double[vars.size()][];
        int h = haloWidth;
        int npx = src.getNX(), npy = src.getNY();
        for (int vi = 0; vi < vars.size(); vi++) {
            VariableType var = vars.get(vi);
            DataField f = src.fields.get(var);
            if (f == null) continue;
            boolean is3D = f.getNDim() == 3;
            int layers = is3D ? nz : 1;
            int is, ie, js, je;
            switch (dir) {
                case WEST -> { is = h; ie = 2 * h; js = h; je = npy - h; }
                case EAST -> { is = npx - 2 * h; ie = npx - h; js = h; je = npy - h; }
                case SOUTH -> { is = h; ie = npx - h; js = h; je = 2 * h; }
                case NORTH -> { is = h; ie = npx - h; js = npy - 2 * h; je = npy - h; }
                case SOUTHWEST -> { is = h; ie = 2 * h; js = h; je = 2 * h; }
                case SOUTHEAST -> { is = npx - 2 * h; ie = npx - h; js = h; je = 2 * h; }
                case NORTHWEST -> { is = h; ie = 2 * h; js = npy - 2 * h; je = npy - h; }
                case NORTHEAST -> { is = npx - 2 * h; ie = npx - h; js = npy - 2 * h; je = npy - h; }
                default -> { is = 0; ie = 0; js = 0; je = 0; }
            }
            int count = (ie - is) * (je - js) * layers;
            double[] arr = new double[count];
            int idx = 0;
            for (int k = 0; k < layers; k++) {
                for (int j = js; j < je; j++) {
                    for (int i = is; i < ie; i++) {
                        int fidx = is3D ? (i + npx * (j + npy * k)) : (i + npx * j);
                        arr[idx++] = f.get(fidx);
                    }
                }
            }
            data[vi] = arr;
        }
        return data;
    }

    private void receiveAndUnpack(ModelState[] states, GridPartitioner.Partition p) {
        ModelState dst = states[p.pid];
        if (dst == null) return;
        for (Direction dir : Direction.values()) {
            Direction opposite = oppositeDirection(dir);
            GridPartitioner.Partition np = getNeighborPartition(p, dir);
            if (np == null) continue;
            int key = p.pid * 16 + opposite.ordinal();
            CompletableFuture<double[][]> cf = sendBuffers.remove(key);
            if (cf == null) continue;
            try {
                double[][] data = cf.get(500, TimeUnit.MILLISECONDS);
                unpackBoundary(dst, dir, data);
            } catch (Exception e) {
                logger.warn("Halo接收超时 P{} from {}: {}", p.pid, dir, e.getMessage());
                applyDefaultBoundary(dst, p, dir);
            }
        }
        finalizePolarBoundaries(dst, p);
    }

    private Direction oppositeDirection(Direction d) {
        return switch (d) {
            case WEST -> Direction.EAST;
            case EAST -> Direction.WEST;
            case SOUTH -> Direction.NORTH;
            case NORTH -> Direction.SOUTH;
            case SOUTHWEST -> Direction.NORTHEAST;
            case NORTHEAST -> Direction.SOUTHWEST;
            case SOUTHEAST -> Direction.NORTHWEST;
            case NORTHWEST -> Direction.SOUTHEAST;
        };
    }

    private void unpackBoundary(ModelState dst, Direction dir, double[][] data) {
        List<VariableType> vars = getExchangedVariables();
        int h = haloWidth;
        int npx = dst.getNX(), npy = dst.getNY();
        for (int vi = 0; vi < vars.size(); vi++) {
            if (data[vi] == null) continue;
            VariableType var = vars.get(vi);
            DataField f = dst.fields.get(var);
            if (f == null) continue;
            boolean is3D = f.getNDim() == 3;
            int layers = is3D ? nz : 1;
            int is, ie, js, je;
            switch (dir) {
                case WEST -> { is = 0; ie = h; js = h; je = npy - h; }
                case EAST -> { is = npx - h; ie = npx; js = h; je = npy - h; }
                case SOUTH -> { is = h; ie = npx - h; js = 0; je = h; }
                case NORTH -> { is = h; ie = npx - h; js = npy - h; je = npy; }
                case SOUTHWEST -> { is = 0; ie = h; js = 0; je = h; }
                case SOUTHEAST -> { is = npx - h; ie = npx; js = 0; je = h; }
                case NORTHWEST -> { is = 0; ie = h; js = npy - h; je = npy; }
                case NORTHEAST -> { is = npx - h; ie = npx; js = npy - h; je = npy; }
                default -> { is = 0; ie = 0; js = 0; je = 0; }
            }
            int idx = 0;
            for (int k = 0; k < layers; k++) {
                for (int j = js; j < je; j++) {
                    for (int i = is; i < ie; i++) {
                        int fidx = is3D ? (i + npx * (j + npy * k)) : (i + npx * j);
                        if (idx < data[vi].length) f.set(fidx, data[vi][idx++]);
                    }
                }
            }
        }
    }

    private void applyDefaultBoundary(ModelState dst, GridPartitioner.Partition p, Direction dir) {
        finalizePolarBoundaries(dst, p);
    }

    private void finalizePolarBoundaries(ModelState dst, GridPartitioner.Partition p) {
        GridDefinition grid = config.getGrid();
        boolean isSouthMost = p.jStart == 0;
        boolean isNorthMost = p.jEnd == ny;
        int npx = dst.getNX(), npy = dst.getNY();
        if (isSouthMost) {
            for (VariableType var : getExchangedVariables()) {
                DataField f = dst.fields.get(var);
                if (f == null) continue;
                boolean is3D = f.getNDim() == 3;
                int layers = is3D ? nz : 1;
                for (int k = 0; k < layers; k++) {
                    for (int j = 0; j < haloWidth; j++) {
                        int jSrc = 2 * haloWidth - j;
                        jSrc = Math.min(npy - 1, Math.max(0, jSrc));
                        for (int i = 0; i < npx; i++) {
                            int iShift = (i + npx / 2) % npx;
                            int sIdx = is3D ? (iShift + npx * (jSrc + npy * k)) : (iShift + npx * jSrc);
                            int dIdx = is3D ? (i + npx * (j + npy * k)) : (i + npx * j);
                            f.set(dIdx, f.get(sIdx));
                        }
                    }
                }
            }
        }
        if (isNorthMost) {
            for (VariableType var : getExchangedVariables()) {
                DataField f = dst.fields.get(var);
                if (f == null) continue;
                boolean is3D = f.getNDim() == 3;
                int layers = is3D ? nz : 1;
                for (int k = 0; k < layers; k++) {
                    for (int j = npy - haloWidth; j < npy; j++) {
                        int jFrom = 2 * (npy - haloWidth) - j - 1;
                        jFrom = Math.min(npy - haloWidth - 1, Math.max(0, jFrom));
                        for (int i = 0; i < npx; i++) {
                            int iShift = (i + npx / 2) % npx;
                            int sIdx = is3D ? (iShift + npx * (jFrom + npy * k)) : (iShift + npx * jFrom);
                            int dIdx = is3D ? (i + npx * (j + npy * k)) : (i + npx * j);
                            f.set(dIdx, f.get(sIdx));
                        }
                    }
                }
            }
        }
    }

    private List<VariableType> getExchangedVariables() {
        List<VariableType> list = new ArrayList<>();
        for (VariableType v : VariableType.values()) {
            if (v.isPrognostic()) list.add(v);
        }
        list.add(VariableType.PSFC);
        return list;
    }

    public void printPerformanceReport() {
        if (exchangeCount > 0) {
            double avgTotal = (totalExchangeNanos.get() / (double) exchangeCount) / 1e6;
            double avgCompute = (totalComputeNanos.get() / (double) exchangeCount) / 1e6;
            double avgOverlap = (totalOverlapNanos.get() / (double) exchangeCount) / 1e6;
            double overlapPct = avgTotal > 0 ? 100.0 * avgOverlap / avgTotal : 0;
            logger.info("===== Halo Exchange 性能报告 =====");
            logger.info("总交换次数: {}", exchangeCount);
            logger.info("平均总耗时: {:.3f}ms", avgTotal);
            logger.info("平均内部计算: {:.3f}ms", avgCompute);
            logger.info("平均重叠时间: {:.3f}ms ({:.1f}%)", avgOverlap, overlapPct);
            logger.info("有效隐藏延迟: {:.3f}ms/步", avgOverlap);
        }
    }

    public void shutdown() {
        if (exchangeExecutor != null) exchangeExecutor.shutdownNow();
        if (computeExecutor != null) computeExecutor.shutdownNow();
        if (sendBuffers != null) sendBuffers.clear();
    }
}
