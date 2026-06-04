package com.proteinviewer.service;

import com.proteinviewer.domain.Atom;
import com.proteinviewer.dto.*;
import com.proteinviewer.mapper.DomainMapper;
import com.proteinviewer.mapper.DtoMapper;
import com.proteinviewer.model.AtomRecord;
import com.proteinviewer.model.ParsedPdb;
import com.proteinviewer.render.SurfaceMesh;
import com.proteinviewer.surface.ElectrostaticGrid;
import com.proteinviewer.surface.MarchingCubesExtractor;
import com.proteinviewer.surface.SurfaceSmoother;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MolecularAnalysisService {

    private static final double HYDROGEN_BOND_MAX_DIST = 3.5;
    private static final double HYDROGEN_BOND_MIN_ANGLE = 120.0;
    private static final double SALT_BRIDGE_MAX_DIST = 4.0;
    private static final double PI_PI_STACKING_MAX_DIST = 7.0;
    private static final double HYDROPHOBIC_MAX_DIST = 5.0;
    private static final double DISULFIDE_MAX_DIST = 2.5;

    private static final Set<String> HYDROPHOBIC_RESIDUES = Set.of(
            "ALA", "VAL", "LEU", "ILE", "PRO", "PHE", "TRP", "MET"
    );

    private static final Set<String> POSITIVE_RESIDUES = Set.of("ARG", "LYS", "HIS");
    private static final Set<String> NEGATIVE_RESIDUES = Set.of("ASP", "GLU");

    private static final Set<String> AROMATIC_RESIDUES = Set.of("PHE", "TYR", "TRP", "HIS");
    private static final Set<String> GLYCOSYLATION_SITES = Set.of("ASN", "SER", "THR");

    public DistanceResultDto calculateDistance(ParsedPdb pdb, int atom1Serial, int atom2Serial) {
        AtomRecord a1 = findAtom(pdb, atom1Serial);
        AtomRecord a2 = findAtom(pdb, atom2Serial);
        if (a1 == null || a2 == null) {
            throw new IllegalArgumentException("Atom not found");
        }
        double dist = Math.sqrt(
                Math.pow(a1.getX() - a2.getX(), 2) +
                Math.pow(a1.getY() - a2.getY(), 2) +
                Math.pow(a1.getZ() - a2.getZ(), 2)
        );
        return DistanceResultDto.builder()
                .atom1Serial(atom1Serial)
                .atom2Serial(atom2Serial)
                .distance(Math.round(dist * 1000.0) / 1000.0)
                .unit("angstrom")
                .build();
    }

    public AngleResultDto calculateAngle(ParsedPdb pdb, int atom1Serial, int atom2Serial, int atom3Serial) {
        AtomRecord a1 = findAtom(pdb, atom1Serial);
        AtomRecord a2 = findAtom(pdb, atom2Serial);
        AtomRecord a3 = findAtom(pdb, atom3Serial);
        if (a1 == null || a2 == null || a3 == null) {
            throw new IllegalArgumentException("Atom not found");
        }

        double[] v1 = {a1.getX() - a2.getX(), a1.getY() - a2.getY(), a1.getZ() - a2.getZ()};
        double[] v2 = {a3.getX() - a2.getX(), a3.getY() - a2.getY(), a3.getZ() - a2.getZ()};

        double dot = v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2];
        double mag1 = Math.sqrt(v1[0] * v1[0] + v1[1] * v1[1] + v1[2] * v1[2]);
        double mag2 = Math.sqrt(v2[0] * v2[0] + v2[1] * v2[1] + v2[2] * v2[2]);

        double cosAngle = Math.max(-1.0, Math.min(1.0, dot / (mag1 * mag2)));
        double angle = Math.toDegrees(Math.acos(cosAngle));

        return AngleResultDto.builder()
                .atom1Serial(atom1Serial)
                .atom2Serial(atom2Serial)
                .atom3Serial(atom3Serial)
                .angle(Math.round(angle * 100.0) / 100.0)
                .unit("degrees")
                .build();
    }

    public InteractionResultDto analyzeInteractions(ParsedPdb pdb, String chainId, int resSeq, double cutoff) {
        List<AtomRecord> centerAtoms = pdb.getAtoms().stream()
                .filter(a -> a.getChainId().equals(chainId) && a.getResidueSeqNumber() == resSeq)
                .collect(Collectors.toList());

        if (centerAtoms.isEmpty()) {
            throw new IllegalArgumentException("Residue not found: " + chainId + ":" + resSeq);
        }

        double[] center = computeCentroid(centerAtoms);
        List<AtomRecord> nearby = pdb.getAtoms().stream()
                .filter(a -> !a.getChainId().equals(chainId) || a.getResidueSeqNumber() != resSeq)
                .filter(a -> distance(center, new double[]{a.getX(), a.getY(), a.getZ()}) <= cutoff)
                .collect(Collectors.toList());

        Map<String, List<AtomRecord>> nearbyByResidue = nearby.stream()
                .collect(Collectors.groupingBy(a -> a.getChainId() + ":" + a.getResidueSeqNumber() + ":" + a.getResidueName()));

        List<InteractionResultDto.NeighborInteraction> interactions = new ArrayList<>();

        for (Map.Entry<String, List<AtomRecord>> entry : nearbyByResidue.entrySet()) {
            String[] parts = entry.getKey().split(":");
            String nChain = parts[0];
            int nResSeq = Integer.parseInt(parts[1]);
            String nResName = parts[2];
            double[] nCentroid = computeCentroid(entry.getValue());
            double dist = distance(center, nCentroid);

            String resCenter = centerAtoms.get(0).getResidueName();

            if (isHydrogenBond(centerAtoms, entry.getValue())) {
                interactions.add(InteractionResultDto.NeighborInteraction.builder()
                        .residue(nResName).chain(nChain).resSeq(nResSeq)
                        .type("hydrogen_bond").distance(Math.round(dist * 100.0) / 100.0)
                        .details("Donor-acceptor distance <= " + HYDROGEN_BOND_MAX_DIST + " Å")
                        .build());
            }

            if (HYDROPHOBIC_RESIDUES.contains(resCenter) && HYDROPHOBIC_RESIDUES.contains(nResName) && dist <= HYDROPHOBIC_MAX_DIST) {
                interactions.add(InteractionResultDto.NeighborInteraction.builder()
                        .residue(nResName).chain(nChain).resSeq(nResSeq)
                        .type("hydrophobic").distance(Math.round(dist * 100.0) / 100.0)
                        .details("Hydrophobic contact between " + resCenter + " and " + nResName)
                        .build());
            }

            if ((POSITIVE_RESIDUES.contains(resCenter) && NEGATIVE_RESIDUES.contains(nResName) ||
                    NEGATIVE_RESIDUES.contains(resCenter) && POSITIVE_RESIDUES.contains(nResName)) && dist <= SALT_BRIDGE_MAX_DIST) {
                interactions.add(InteractionResultDto.NeighborInteraction.builder()
                        .residue(nResName).chain(nChain).resSeq(nResSeq)
                        .type("salt_bridge").distance(Math.round(dist * 100.0) / 100.0)
                        .details("Electrostatic interaction between " + resCenter + " and " + nResName)
                        .build());
            }

            if (AROMATIC_RESIDUES.contains(resCenter) && AROMATIC_RESIDUES.contains(nResName) && dist <= PI_PI_STACKING_MAX_DIST) {
                interactions.add(InteractionResultDto.NeighborInteraction.builder()
                        .residue(nResName).chain(nChain).resSeq(nResSeq)
                        .type("pi_pi_stacking").distance(Math.round(dist * 100.0) / 100.0)
                        .details("π-π stacking between " + resCenter + " and " + nResName)
                        .build());
            }
        }

        return InteractionResultDto.builder()
                .centerResidue(centerAtoms.get(0).getResidueName())
                .centerChain(chainId)
                .centerResSeq(resSeq)
                .interactions(interactions)
                .build();
    }

    public AlignmentResultDto alignStructures(ParsedPdb pdb1, ParsedPdb pdb2) {
        List<AtomRecord> atoms1 = pdb1.getAtoms().stream()
                .filter(a -> !a.isHetatm() && a.getAtomName().equals("CA"))
                .collect(Collectors.toList());
        List<AtomRecord> atoms2 = pdb2.getAtoms().stream()
                .filter(a -> !a.isHetatm() && a.getAtomName().equals("CA"))
                .collect(Collectors.toList());

        int n = Math.min(atoms1.size(), atoms2.size());
        if (n < 3) {
            throw new IllegalArgumentException("Need at least 3 CA atoms for alignment");
        }

        double[][] coords1 = new double[n][3];
        double[][] coords2 = new double[n][3];
        for (int i = 0; i < n; i++) {
            coords1[i][0] = atoms1.get(i).getX();
            coords1[i][1] = atoms1.get(i).getY();
            coords1[i][2] = atoms1.get(i).getZ();
            coords2[i][0] = atoms2.get(i).getX();
            coords2[i][1] = atoms2.get(i).getY();
            coords2[i][2] = atoms2.get(i).getZ();
        }

        double[] centroid1 = computeCentroidArray(coords1, n);
        double[] centroid2 = computeCentroidArray(coords2, n);

        for (int i = 0; i < n; i++) {
            coords1[i][0] -= centroid1[0]; coords1[i][1] -= centroid1[1]; coords1[i][2] -= centroid1[2];
            coords2[i][0] -= centroid2[0]; coords2[i][1] -= centroid2[1]; coords2[i][2] -= centroid2[2];
        }

        double[][] h = new double[3][3];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    h[j][k] += coords1[i][j] * coords2[i][k];
                }
            }
        }

        double[][] rotation = kabschRotation(h);
        double[] translation = new double[3];
        for (int i = 0; i < 3; i++) {
            translation[i] = centroid1[i] - (rotation[i][0] * centroid2[0] + rotation[i][1] * centroid2[1] + rotation[i][2] * centroid2[2]);
        }

        double totalSqDist = 0;
        List<AlignmentResultDto.ResidueRmsd> perResidue = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double rx = rotation[0][0] * coords2[i][0] + rotation[0][1] * coords2[i][1] + rotation[0][2] * coords2[i][2];
            double ry = rotation[1][0] * coords2[i][0] + rotation[1][1] * coords2[i][1] + rotation[1][2] * coords2[i][2];
            double rz = rotation[2][0] * coords2[i][0] + rotation[2][1] * coords2[i][1] + rotation[2][2] * coords2[i][2];

            double dx = coords1[i][0] - rx;
            double dy = coords1[i][1] - ry;
            double dz = coords1[i][2] - rz;
            double sqDist = dx * dx + dy * dy + dz * dz;
            totalSqDist += sqDist;

            AtomRecord a = atoms1.get(i);
            perResidue.add(AlignmentResultDto.ResidueRmsd.builder()
                    .residueName(a.getResidueName())
                    .chainId(a.getChainId())
                    .resSeq(a.getResidueSeqNumber())
                    .rmsd(Math.round(Math.sqrt(sqDist) * 100.0) / 100.0)
                    .build());
        }

        double rmsd = Math.sqrt(totalSqDist / n);

        return AlignmentResultDto.builder()
                .structure1Id(null)
                .structure2Id(null)
                .rmsd(Math.round(rmsd * 1000.0) / 1000.0)
                .rotationMatrix(rotation)
                .translationVector(translation)
                .perResidueRmsd(perResidue)
                .alignedAtomCount(n)
                .build();
    }

    private double[][] kabschRotation(double[][] h) {
        double[][] u = new double[3][3];
        double[] s = new double[3];
        double[][] vt = new double[3][3];
        svd3x3(h, u, s, vt);

        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    r[i][j] += u[i][k] * vt[k][j];
                }
            }
        }

        double det = r[0][0] * (r[1][1] * r[2][2] - r[1][2] * r[2][1])
                - r[0][1] * (r[1][0] * r[2][2] - r[1][2] * r[2][0])
                + r[0][2] * (r[1][0] * r[2][1] - r[1][1] * r[2][0]);

        if (det < 0) {
            for (int i = 0; i < 3; i++) {
                u[i][2] = -u[i][2];
            }
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    r[i][j] = 0;
                    for (int k = 0; k < 3; k++) {
                        r[i][j] += u[i][k] * vt[k][j];
                    }
                }
            }
        }

        return r;
    }

    private void svd3x3(double[][] a, double[][] u, double[] s, double[][] vt) {
        double[][] ata = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    ata[i][j] += a[k][i] * a[k][j];
                }
            }
        }

        double[] eigenvalues = new double[3];
        double[][] eigenvectors = new double[3][3];
        symmetricEigen3x3(ata, eigenvalues, eigenvectors);

        for (int i = 0; i < 3; i++) {
            s[i] = Math.sqrt(Math.max(0, eigenvalues[i]));
            for (int j = 0; j < 3; j++) {
                vt[i][j] = eigenvectors[j][i];
            }
        }

        for (int i = 0; i < 3; i++) {
            if (s[i] > 1e-10) {
                for (int j = 0; j < 3; j++) {
                    u[j][i] = 0;
                    for (int k = 0; k < 3; k++) {
                        u[j][i] += a[j][k] * eigenvectors[k][i] / s[i];
                    }
                }
            } else {
                u[i][i] = 1;
            }
        }

        orthonormalize(u, 0);
    }

    private void symmetricEigen3x3(double[][] a, double[] eigenvalues, double[][] eigenvectors) {
        for (int i = 0; i < 3; i++) {
            eigenvectors[i][i] = 1.0;
        }

        for (int iter = 0; iter < 100; iter++) {
            double maxOff = 0;
            int p = 0, q = 1;
            for (int i = 0; i < 3; i++) {
                for (int j = i + 1; j < 3; j++) {
                    if (Math.abs(a[i][j]) > maxOff) {
                        maxOff = Math.abs(a[i][j]);
                        p = i; q = j;
                    }
                }
            }
            if (maxOff < 1e-12) break;

            double app = a[p][p], aqq = a[q][q], apq = a[p][q];
            double theta = (aqq - app) / (2 * apq);
            double t = theta >= 0
                    ? 1.0 / (theta + Math.sqrt(1 + theta * theta))
                    : -1.0 / (-theta + Math.sqrt(1 + theta * theta));
            double c = 1.0 / Math.sqrt(1 + t * t);
            double s = t * c;

            a[p][p] = c * c * app - 2 * s * c * apq + s * s * aqq;
            a[q][q] = s * s * app + 2 * s * c * apq + c * c * aqq;
            a[p][q] = 0; a[q][p] = 0;

            for (int r = 0; r < 3; r++) {
                if (r != p && r != q) {
                    double arp = a[r][p], arq = a[r][q];
                    a[r][p] = c * arp - s * arq; a[p][r] = a[r][p];
                    a[r][q] = s * arp + c * arq; a[q][r] = a[r][q];
                }
                double erp = eigenvectors[r][p], erq = eigenvectors[r][q];
                eigenvectors[r][p] = c * erp - s * erq;
                eigenvectors[r][q] = s * erp + c * erq;
            }
        }

        for (int i = 0; i < 3; i++) {
            eigenvalues[i] = a[i][i];
        }

        int[] order = {0, 1, 2};
        for (int i = 0; i < 2; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (eigenvalues[order[i]] < eigenvalues[order[j]]) {
                    int tmp = order[i]; order[i] = order[j]; order[j] = tmp;
                }
            }
        }

        double[] sortedEigen = new double[3];
        double[][] sortedVecs = new double[3][3];
        for (int i = 0; i < 3; i++) {
            sortedEigen[i] = eigenvalues[order[i]];
            for (int j = 0; j < 3; j++) {
                sortedVecs[j][i] = eigenvectors[j][order[i]];
            }
        }
        System.arraycopy(sortedEigen, 0, eigenvalues, 0, 3);
        for (int i = 0; i < 3; i++) {
            System.arraycopy(sortedVecs[i], 0, eigenvectors[i], 0, 3);
        }
    }

    private void orthonormalize(double[][] u, int startCol) {
        for (int i = 0; i < 3; i++) {
            double norm = 0;
            for (int j = 0; j < 3; j++) norm += u[j][i] * u[j][i];
            norm = Math.sqrt(norm);
            if (norm > 1e-10) {
                for (int j = 0; j < 3; j++) u[j][i] /= norm;
            }
        }

        double[] cross = new double[3];
        cross[0] = u[1][0] * u[2][1] - u[2][0] * u[1][1];
        cross[1] = u[2][0] * u[0][1] - u[0][0] * u[2][1];
        cross[2] = u[0][0] * u[1][1] - u[1][0] * u[0][1];
        double norm = 0;
        for (int j = 0; j < 3; j++) norm += cross[j] * cross[j];
        norm = Math.sqrt(norm);
        if (norm > 1e-10) {
            for (int j = 0; j < 3; j++) u[j][2] = cross[j] / norm;
        }
    }

    public List<BatchAnalysisResultDto.DisulfideBond> detectDisulfideBonds(ParsedPdb pdb, Long structureId) {
        List<AtomRecord> sgAtoms = pdb.getAtoms().stream()
                .filter(a -> a.getAtomName().equals("SG") && a.getResidueName().equals("CYS"))
                .collect(Collectors.toList());

        List<BatchAnalysisResultDto.DisulfideBond> bonds = new ArrayList<>();
        for (int i = 0; i < sgAtoms.size(); i++) {
            for (int j = i + 1; j < sgAtoms.size(); j++) {
                AtomRecord a1 = sgAtoms.get(i);
                AtomRecord a2 = sgAtoms.get(j);
                double dist = Math.sqrt(
                        Math.pow(a1.getX() - a2.getX(), 2) +
                        Math.pow(a1.getY() - a2.getY(), 2) +
                        Math.pow(a1.getZ() - a2.getZ(), 2)
                );
                if (dist <= DISULFIDE_MAX_DIST) {
                    bonds.add(BatchAnalysisResultDto.DisulfideBond.builder()
                            .structureId(structureId)
                            .chain1(a1.getChainId()).resSeq1(a1.getResidueSeqNumber())
                            .chain2(a2.getChainId()).resSeq2(a2.getResidueSeqNumber())
                            .distance(Math.round(dist * 1000.0) / 1000.0)
                            .build());
                }
            }
        }
        return bonds;
    }

    public List<BatchAnalysisResultDto.GlycosylationSite> predictGlycosylationSites(ParsedPdb pdb, Long structureId) {
        List<BatchAnalysisResultDto.GlycosylationSite> sites = new ArrayList<>();
        List<AtomRecord> nxAtoms = pdb.getAtoms().stream()
                .filter(a -> a.getAtomName().equals("ND2") && a.getResidueName().equals("ASN"))
                .collect(Collectors.toList());

        for (AtomRecord nd2 : nxAtoms) {
            List<AtomRecord> serThr = pdb.getAtoms().stream()
                    .filter(a -> (a.getResidueName().equals("SER") || a.getResidueName().equals("THR"))
                            && a.getAtomName().equals("OG") || a.getAtomName().equals("OG1"))
                    .filter(a -> Math.abs(a.getResidueSeqNumber() - nd2.getResidueSeqNumber()) == 2)
                    .collect(Collectors.toList());

            if (!serThr.isEmpty()) {
                sites.add(BatchAnalysisResultDto.GlycosylationSite.builder()
                        .structureId(structureId)
                        .chain(nd2.getChainId())
                        .resSeq(nd2.getResidueSeqNumber())
                        .residueName("ASN")
                        .type("N-linked")
                        .confidence(0.85)
                        .build());
            }
        }

        pdb.getAtoms().stream()
                .filter(a -> GLYCOSYLATION_SITES.contains(a.getResidueName())
                        && (a.getAtomName().equals("OG") || a.getAtomName().equals("OG1") || a.getAtomName().equals("OD1")))
                .forEach(a -> sites.add(BatchAnalysisResultDto.GlycosylationSite.builder()
                        .structureId(structureId)
                        .chain(a.getChainId())
                        .resSeq(a.getResidueSeqNumber())
                        .residueName(a.getResidueName())
                        .type("O-linked")
                        .confidence(0.6)
                        .build()));

        return sites;
    }

    public BatchAnalysisResultDto.BFactorStats analyzeBFactor(ParsedPdb pdb, Long structureId) {
        List<Double> bfactors = pdb.getAtoms().stream()
                .filter(a -> !a.isHetatm())
                .map(AtomRecord::getTempFactor)
                .collect(Collectors.toList());

        if (bfactors.isEmpty()) {
            return BatchAnalysisResultDto.BFactorStats.builder().structureId(structureId).build();
        }

        double mean = bfactors.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = bfactors.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double min = bfactors.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = bfactors.stream().mapToDouble(Double::doubleValue).max().orElse(0);

        List<Double> sorted = bfactors.stream().sorted().collect(Collectors.toList());
        double median = sorted.size() % 2 == 0 ?
                (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2 :
                sorted.get(sorted.size() / 2);

        return BatchAnalysisResultDto.BFactorStats.builder()
                .structureId(structureId)
                .mean(Math.round(mean * 100.0) / 100.0)
                .stdDev(Math.round(stdDev * 100.0) / 100.0)
                .min(Math.round(min * 100.0) / 100.0)
                .max(Math.round(max * 100.0) / 100.0)
                .median(Math.round(median * 100.0) / 100.0)
                .build();
    }

    public ElectrostaticSurfaceDto computeElectrostaticSurface(ParsedPdb pdb, Long structureId) {
        DomainMapper domainMapper = new DomainMapper();
        List<Atom> atoms = pdb.getAtoms().stream()
                .map(domainMapper::toDomainAtom)
                .collect(Collectors.toList());

        ElectrostaticGrid grid = ElectrostaticGrid.compute(atoms, 65, 5.0);
        SurfaceMesh mesh = MarchingCubesExtractor.extract(grid, 0.5, structureId);
        SurfaceMesh smoothed = SurfaceSmoother.smooth(mesh, 2);

        DtoMapper dtoMapper = new DtoMapper();
        return dtoMapper.toElectrostaticSurfaceDto(smoothed, structureId);
    }


    private boolean isHydrogenBond(List<AtomRecord> donor, List<AtomRecord> acceptor) {
        List<String> hDonors = List.of("N", "NE", "NH1", "NH2", "NZ", "OG", "OG1", "OH", "NE2", "ND1", "NE1");
        List<String> hAcceptors = List.of("O", "OD1", "OD2", "OE1", "OE2", "OG", "OG1", "OH", "ND1", "NE2");

        for (AtomRecord d : donor) {
            if (hDonors.contains(d.getAtomName())) {
                for (AtomRecord a : acceptor) {
                    if (hAcceptors.contains(a.getAtomName())) {
                        double dist = Math.sqrt(
                                Math.pow(d.getX() - a.getX(), 2) +
                                Math.pow(d.getY() - a.getY(), 2) +
                                Math.pow(d.getZ() - a.getZ(), 2)
                        );
                        if (dist <= HYDROGEN_BOND_MAX_DIST) return true;
                    }
                }
            }
        }
        return false;
    }

    private AtomRecord findAtom(ParsedPdb pdb, int serial) {
        return pdb.getAtoms().stream()
                .filter(a -> a.getSerialNumber() == serial)
                .findFirst()
                .orElse(null);
    }

    private double[] computeCentroid(List<AtomRecord> atoms) {
        double cx = 0, cy = 0, cz = 0;
        for (AtomRecord a : atoms) {
            cx += a.getX(); cy += a.getY(); cz += a.getZ();
        }
        int n = atoms.size();
        return new double[]{cx / n, cy / n, cz / n};
    }

    private double[] computeCentroidArray(double[][] coords, int n) {
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            cx += coords[i][0]; cy += coords[i][1]; cz += coords[i][2];
        }
        return new double[]{cx / n, cy / n, cz / n};
    }

    private double distance(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

}
