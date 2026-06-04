package com.proteinviewer.mapper;

import com.proteinviewer.domain.Structure;
import com.proteinviewer.domain.ValidationWarning;
import com.proteinviewer.dto.AngleResultDto;
import com.proteinviewer.dto.AtomInfoDto;
import com.proteinviewer.dto.BondInfoDto;
import com.proteinviewer.dto.DistanceResultDto;
import com.proteinviewer.dto.ElectrostaticSurfaceDto;
import com.proteinviewer.dto.PdbDataDto;
import com.proteinviewer.dto.StructureUploadResponse;
import com.proteinviewer.dto.ValidationWarningDto;
import com.proteinviewer.render.CylinderPrimitive;
import com.proteinviewer.render.RenderModel;
import com.proteinviewer.render.SpherePrimitive;
import com.proteinviewer.render.SurfaceMesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DtoMapper {

    public AtomInfoDto toAtomInfoDto(SpherePrimitive sphere) {
        return AtomInfoDto.builder()
                .serialNumber(sphere.getAtomSerial())
                .x(sphere.getX())
                .y(sphere.getY())
                .z(sphere.getZ())
                .element(sphere.getAtomElement())
                .build();
    }

    public PdbDataDto toPdbDataDto(Structure structure, RenderModel renderModel, Long structureId) {
        Map<Integer, SpherePrimitive> sphereMap = renderModel.getAtomSpheres().stream()
                .collect(Collectors.toMap(SpherePrimitive::getAtomSerial, s -> s));

        List<AtomInfoDto> atoms = new ArrayList<>();
        for (com.proteinviewer.domain.Atom domainAtom : structure.getAtoms()) {
            SpherePrimitive sphere = sphereMap.get(domainAtom.getSerialNumber());
            AtomInfoDto dto = AtomInfoDto.builder()
                    .serialNumber(domainAtom.getSerialNumber())
                    .atomName(domainAtom.getAtomName())
                    .residueName(domainAtom.getResidueName())
                    .chainId(domainAtom.getChainId())
                    .residueSeqNumber(domainAtom.getResidueSeqNumber())
                    .x(domainAtom.getX())
                    .y(domainAtom.getY())
                    .z(domainAtom.getZ())
                    .element(domainAtom.getElement())
                    .tempFactor(domainAtom.getTempFactor())
                    .isHetatm(domainAtom.isHetatm())
                    .build();
            atoms.add(dto);
        }

        List<BondInfoDto> bonds = new ArrayList<>();
        for (com.proteinviewer.domain.Bond domainBond : structure.getBonds()) {
            bonds.add(BondInfoDto.builder()
                    .atomSerial(domainBond.getAtomSerial())
                    .bondedAtoms(domainBond.getBondedAtoms())
                    .build());
        }

        List<String> chainIds = new ArrayList<>();
        for (com.proteinviewer.domain.Chain chain : structure.getChains()) {
            chainIds.add(chain.getId());
        }

        return PdbDataDto.builder()
                .structureId(structureId)
                .pdbId(structure.getPdbId())
                .title(structure.getTitle())
                .atoms(atoms)
                .bonds(bonds)
                .chainIds(chainIds)
                .totalAtoms(structure.getAtoms().size())
                .totalResidues(structure.getTotalResidues())
                .build();
    }

    public ElectrostaticSurfaceDto toElectrostaticSurfaceDto(SurfaceMesh mesh, Long structureId) {
        return ElectrostaticSurfaceDto.builder()
                .structureId(structureId)
                .vertices(mesh.getVertices())
                .indices(mesh.getIndices())
                .potentials(mesh.getPotentials())
                .minPotential(mesh.getMinPotential())
                .maxPotential(mesh.getMaxPotential())
                .gridResolution(mesh.getGridResolution())
                .build();
    }

    public StructureUploadResponse toUploadResponse(Structure structure, Long id, String name,
                                                     List<ValidationWarning> warnings) {
        List<ValidationWarningDto> warningDtos = new ArrayList<>();
        for (ValidationWarning w : warnings) {
            warningDtos.add(toWarningDto(w));
        }

        return StructureUploadResponse.builder()
                .id(id)
                .name(name)
                .pdbId(structure.getPdbId())
                .atomCount(structure.getAtoms().size())
                .residueCount(structure.getTotalResidues())
                .bondCount(structure.getBonds().size())
                .warnings(warningDtos)
                .build();
    }

    public ValidationWarningDto toWarningDto(ValidationWarning w) {
        return new ValidationWarningDto(
                w.getLineNumber(),
                w.getField(),
                w.getMessage(),
                w.getSeverity()
        );
    }

    public DistanceResultDto toDistanceResult(double dist, int atom1, int atom2) {
        return DistanceResultDto.builder()
                .atom1Serial(atom1)
                .atom2Serial(atom2)
                .distance(dist)
                .unit("angstrom")
                .build();
    }

    public AngleResultDto toAngleResult(double angle, int a1, int a2, int a3) {
        return AngleResultDto.builder()
                .atom1Serial(a1)
                .atom2Serial(a2)
                .atom3Serial(a3)
                .angle(angle)
                .unit("degrees")
                .build();
    }
}
