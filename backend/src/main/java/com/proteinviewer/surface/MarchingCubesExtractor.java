package com.proteinviewer.surface;

import com.proteinviewer.render.SurfaceMesh;
import com.proteinviewer.service.MarchingCubesTables;
import java.util.*;

public class MarchingCubesExtractor {

    public static SurfaceMesh extract(ElectrostaticGrid grid, double isoValue, Long structureId) {
        int[] edgeTable = MarchingCubesTables.EDGE_TABLE;
        int[][] triTable = MarchingCubesTables.TRI_TABLE;

        double[][][] density = grid.getDensity();
        double[][][] potential = grid.getPotential();
        int nx = grid.getGridSize();
        int ny = grid.getGridSize();
        int nz = grid.getGridSize();
        double ox = grid.getOrigin()[0];
        double oy = grid.getOrigin()[1];
        double oz = grid.getOrigin()[2];
        double dx = grid.getSpacing()[0];
        double dy = grid.getSpacing()[1];
        double dz = grid.getSpacing()[2];

        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        List<Float> potentials = new ArrayList<>();

        Map<Long, Integer> vertexMap = new HashMap<>();
        int vertexCount = 0;

        for (int ix = 0; ix < nx - 1; ix++) {
            for (int iy = 0; iy < ny - 1; iy++) {
                for (int iz = 0; iz < nz - 1; iz++) {
                    double[] v = new double[8];
                    double[] p = new double[8];
                    v[0] = density[ix][iy][iz]; p[0] = potential[ix][iy][iz];
                    v[1] = density[ix+1][iy][iz]; p[1] = potential[ix+1][iy][iz];
                    v[2] = density[ix+1][iy+1][iz]; p[2] = potential[ix+1][iy+1][iz];
                    v[3] = density[ix][iy+1][iz]; p[3] = potential[ix][iy+1][iz];
                    v[4] = density[ix][iy][iz+1]; p[4] = potential[ix][iy][iz+1];
                    v[5] = density[ix+1][iy][iz+1]; p[5] = potential[ix+1][iy][iz+1];
                    v[6] = density[ix+1][iy+1][iz+1]; p[6] = potential[ix+1][iy+1][iz+1];
                    v[7] = density[ix][iy+1][iz+1]; p[7] = potential[ix][iy+1][iz+1];

                    int cubeIndex = 0;
                    for (int i = 0; i < 8; i++) {
                        if (v[i] < isoValue) cubeIndex |= (1 << i);
                    }

                    if (edgeTable[cubeIndex] == 0) continue;

                    double[][] vertPos = {
                            {ox + ix * dx, oy + iy * dy, oz + iz * dz},
                            {ox + (ix+1) * dx, oy + iy * dy, oz + iz * dz},
                            {ox + (ix+1) * dx, oy + (iy+1) * dy, oz + iz * dz},
                            {ox + ix * dx, oy + (iy+1) * dy, oz + iz * dz},
                            {ox + ix * dx, oy + iy * dy, oz + (iz+1) * dz},
                            {ox + (ix+1) * dx, oy + iy * dy, oz + (iz+1) * dz},
                            {ox + (ix+1) * dx, oy + (iy+1) * dy, oz + (iz+1) * dz},
                            {ox + ix * dx, oy + (iy+1) * dy, oz + (iz+1) * dz}
                    };

                    int[][] edgeVertices = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
                    int[] edgeIndices = new int[12];

                    for (int e = 0; e < 12; e++) {
                        if ((edgeTable[cubeIndex] & (1 << e)) != 0) {
                            int a = edgeVertices[e][0], b = edgeVertices[e][1];
                            double t = (isoValue - v[a]) / (v[b] - v[a]);
                            float vx = (float) (vertPos[a][0] + t * (vertPos[b][0] - vertPos[a][0]));
                            float vy = (float) (vertPos[a][1] + t * (vertPos[b][1] - vertPos[a][1]));
                            float vz = (float) (vertPos[a][2] + t * (vertPos[b][2] - vertPos[a][2]));
                            float pot = (float) (p[a] + t * (p[b] - p[a]));

                            long key = ((long) ix * ny * nz + (long) iy * nz + iz) * 12 + e;
                            if (!vertexMap.containsKey(key)) {
                                vertexMap.put(key, vertexCount);
                                vertices.add(vx); vertices.add(vy); vertices.add(vz);
                                potentials.add(pot);
                                vertexCount++;
                            }
                            edgeIndices[e] = vertexMap.get(key);
                        }
                    }

                    for (int t = 0; triTable[cubeIndex][t] != -1; t += 3) {
                        indices.add(edgeIndices[triTable[cubeIndex][t]]);
                        indices.add(edgeIndices[triTable[cubeIndex][t+1]]);
                        indices.add(edgeIndices[triTable[cubeIndex][t+2]]);
                    }
                }
            }
        }

        double minP = potentials.stream().mapToDouble(Float::doubleValue).min().orElse(0);
        double maxP = potentials.stream().mapToDouble(Float::doubleValue).max().orElse(0);

        return new SurfaceMesh(vertices, indices, potentials, minP, maxP, grid.getGridSize());
    }
}
