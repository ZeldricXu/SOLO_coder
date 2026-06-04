package com.proteinviewer.service;

import com.proteinviewer.domain.Structure;
import com.proteinviewer.domain.ValidationWarning;
import com.proteinviewer.dto.*;
import com.proteinviewer.mapper.DomainMapper;
import com.proteinviewer.mapper.DtoMapper;
import com.proteinviewer.mapper.RenderMapper;
import com.proteinviewer.model.*;
import com.proteinviewer.render.RenderModel;
import com.proteinviewer.render.SurfaceMesh;
import com.proteinviewer.surface.ElectrostaticGrid;
import com.proteinviewer.surface.MarchingCubesExtractor;
import com.proteinviewer.surface.SurfaceSmoother;
import com.proteinviewer.util.PdbParser;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class StructureService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StructureService.class);

    private final PdbParser pdbParser;
    private final MinioClient minioClient;
    private final MolecularAnalysisService analysisService;
    private final BatchAnalysisAsyncService batchAnalysisAsyncService;
    private final DomainMapper domainMapper = new DomainMapper();
    private final RenderMapper renderMapper = new RenderMapper();
    private final DtoMapper dtoMapper = new DtoMapper();

    private final ConcurrentHashMap<Long, ParsedPdb> pdbCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ProteinStructure> structureStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Structure> domainCache = new ConcurrentHashMap<>();
    private long idCounter = 1;

    public StructureService(PdbParser pdbParser, MinioClient minioClient,
                            MolecularAnalysisService analysisService,
                            BatchAnalysisAsyncService batchAnalysisAsyncService) {
        this.pdbParser = pdbParser;
        this.minioClient = minioClient;
        this.analysisService = analysisService;
        this.batchAnalysisAsyncService = batchAnalysisAsyncService;
    }

    public StructureUploadResponse uploadStructure(MultipartFile file, String name, Long projectId) {
        try {
            String fileName = file.getOriginalFilename();
            String storageKey = UUID.randomUUID().toString() + "/" + fileName;

            try (InputStream is = file.getInputStream()) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket("protein-files")
                        .object(storageKey)
                        .stream(is, file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build());
            } catch (Exception e) {
                log.warn("MinIO not available, storing locally");
            }

            ParsedPdb parsed;
            try (InputStream is = file.getInputStream()) {
                parsed = pdbParser.parse(is);
            }

            Long id = idCounter++;
            ProteinStructure structure = ProteinStructure.builder()
                    .id(id)
                    .name(name != null ? name : fileName)
                    .pdbId(parsed.getPdbId())
                    .fileName(fileName)
                    .storageKey(storageKey)
                    .fileSize(file.getSize())
                    .atomCount(parsed.getTotalAtoms())
                    .residueCount(parsed.getTotalResidues())
                    .bondCount(parsed.getBonds().size())
                    .title(parsed.getTitle())
                    .projectId(projectId != null ? projectId : 1L)
                    .uploadedBy(1L)
                    .build();

            structureStore.put(id, structure);
            pdbCache.put(id, parsed);

            Structure domainStructure = domainMapper.toDomainStructure(parsed);
            domainCache.put(id, domainStructure);

            List<ValidationWarning> domainWarnings = domainStructure.getValidation().getWarnings();
            List<ValidationWarningDto> warnings = domainWarnings.stream()
                    .map(dtoMapper::toWarningDto)
                    .collect(Collectors.toList());

            return dtoMapper.toUploadResponse(domainStructure, id, structure.getName(), domainWarnings);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload structure: " + e.getMessage(), e);
        }
    }

    public PdbDataDto getStructureData(Long id) {
        ParsedPdb parsed = getParsedPdb(id);
        Structure domainStructure = getDomainStructure(id);

        RenderModel renderModel = renderMapper.toRenderModel(domainStructure);

        return dtoMapper.toPdbDataDto(domainStructure, renderModel, id);
    }

    public DistanceResultDto calculateDistance(Long id, int atom1, int atom2) {
        return analysisService.calculateDistance(getParsedPdb(id), atom1, atom2);
    }

    public AngleResultDto calculateAngle(Long id, int atom1, int atom2, int atom3) {
        return analysisService.calculateAngle(getParsedPdb(id), atom1, atom2, atom3);
    }

    public InteractionResultDto analyzeInteractions(Long id, String chainId, int resSeq, double cutoff) {
        return analysisService.analyzeInteractions(getParsedPdb(id), chainId, resSeq, cutoff);
    }

    public AlignmentResultDto alignStructures(Long id1, Long id2) {
        return analysisService.alignStructures(getParsedPdb(id1), getParsedPdb(id2));
    }

    public ElectrostaticSurfaceDto computeElectrostaticSurface(Long id) {
        Structure domainStructure = getDomainStructure(id);

        ElectrostaticGrid grid = ElectrostaticGrid.compute(domainStructure.getAtoms(), 65, 5.0);
        SurfaceMesh mesh = MarchingCubesExtractor.extract(grid, 0.5, id);
        SurfaceMesh smoothed = SurfaceSmoother.smooth(mesh, 2);

        return dtoMapper.toElectrostaticSurfaceDto(smoothed, id);
    }

    public BatchAnalysisResultDto batchAnalysis(List<Long> structureIds) {
        BatchAnalysisResultDto.Builder result = BatchAnalysisResultDto.builder()
                .taskId(UUID.randomUUID().toString())
                .status("COMPLETED")
                .structureIds(structureIds);

        int n = structureIds.size();
        double[][] rmsdMatrix = new double[n][n];
        List<String> names = new ArrayList<>();
        List<BatchAnalysisResultDto.DisulfideBond> allDisulfide = new ArrayList<>();
        List<BatchAnalysisResultDto.GlycosylationSite> allGlyco = new ArrayList<>();
        Map<Long, BatchAnalysisResultDto.BFactorStats> bfactorMap = new HashMap<>();

        for (int i = 0; i < n; i++) {
            ParsedPdb pdb1 = getParsedPdb(structureIds.get(i));
            ProteinStructure s1 = structureStore.get(structureIds.get(i));
            names.add(s1 != null ? s1.getName() : "Structure " + structureIds.get(i));
            rmsdMatrix[i][i] = 0.0;

            allDisulfide.addAll(analysisService.detectDisulfideBonds(pdb1, structureIds.get(i)));
            allGlyco.addAll(analysisService.predictGlycosylationSites(pdb1, structureIds.get(i)));
            bfactorMap.put(structureIds.get(i), analysisService.analyzeBFactor(pdb1, structureIds.get(i)));

            for (int j = i + 1; j < n; j++) {
                ParsedPdb pdb2 = getParsedPdb(structureIds.get(j));
                try {
                    AlignmentResultDto alignment = analysisService.alignStructures(pdb1, pdb2);
                    rmsdMatrix[i][j] = alignment.getRmsd();
                    rmsdMatrix[j][i] = alignment.getRmsd();
                } catch (Exception e) {
                    rmsdMatrix[i][j] = -1;
                    rmsdMatrix[j][i] = -1;
                }
            }
        }

        return result.rmsdMatrix(rmsdMatrix)
                .structureNames(names)
                .disulfideBonds(allDisulfide)
                .glycosylationSites(allGlyco)
                .bfactorStats(bfactorMap)
                .build();
    }

    public BatchTaskStatusDto submitBatchAnalysis(List<Long> structureIds) {
        return batchAnalysisAsyncService.submitBatch(structureIds, this::getParsedPdb, id -> {
            ProteinStructure s = structureStore.get(id);
            return s != null ? s.getName() : null;
        });
    }

    public BatchTaskStatusDto getBatchTaskStatus(String taskId) {
        return batchAnalysisAsyncService.getTaskStatus(taskId);
    }

    public BatchAnalysisResultDto getBatchTaskResult(String taskId) {
        return batchAnalysisAsyncService.getTaskResult(taskId);
    }

    public BatchTaskStatusDto submitElectrostaticSurface(Long id) {
        return batchAnalysisAsyncService.submitElectrostaticSurface(id, this::getParsedPdb);
    }

    public BatchTaskStatusDto submitMultiStructureAlignment(List<Long> structureIds) {
        return batchAnalysisAsyncService.submitMultiStructureAlignment(structureIds, this::getParsedPdb);
    }

    public List<BatchTaskStatusDto> getActiveTasks() {
        return batchAnalysisAsyncService.getActiveTasks();
    }

    private ParsedPdb getParsedPdb(Long id) {
        ParsedPdb cached = pdbCache.get(id);
        if (cached != null) return cached;

        ProteinStructure structure = structureStore.get(id);
        if (structure == null) throw new IllegalArgumentException("Structure not found: " + id);

        throw new IllegalArgumentException("Structure data not in cache: " + id);
    }

    private Structure getDomainStructure(Long id) {
        Structure cached = domainCache.get(id);
        if (cached != null) return cached;

        ParsedPdb parsed = getParsedPdb(id);
        Structure domainStructure = domainMapper.toDomainStructure(parsed);
        domainCache.put(id, domainStructure);
        return domainStructure;
    }

    public List<ProteinStructure> listStructures() {
        return new ArrayList<>(structureStore.values());
    }

    public ParsedPdb getParsedPdbDirect(Long id) {
        return getParsedPdb(id);
    }

    public String getStructureName(Long id) {
        ProteinStructure s = structureStore.get(id);
        return s != null ? s.getName() : "Structure " + id;
    }
}
