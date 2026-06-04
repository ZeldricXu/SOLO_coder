package com.proteinviewer.mapper;

import com.proteinviewer.domain.Atom;
import com.proteinviewer.domain.Bond;
import com.proteinviewer.domain.Chain;
import com.proteinviewer.domain.Residue;
import com.proteinviewer.domain.Structure;
import com.proteinviewer.domain.ValidationResult;
import com.proteinviewer.domain.ValidationWarning;
import com.proteinviewer.model.AtomRecord;
import com.proteinviewer.model.BondRecord;
import com.proteinviewer.model.ParsedPdb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DomainMapper {

    public Atom toDomainAtom(AtomRecord record) {
        return new Atom(
                record.getSerialNumber(),
                record.getAtomName(),
                record.getAltLocation(),
                record.getResidueName(),
                record.getChainId(),
                record.getResidueSeqNumber(),
                record.getICode(),
                record.getX(),
                record.getY(),
                record.getZ(),
                record.getOccupancy(),
                record.getTempFactor(),
                record.getElement(),
                record.getCharge(),
                record.isHetatm()
        );
    }

    public Bond toDomainBond(BondRecord record) {
        return new Bond(record.getAtomSerial(), new ArrayList<>(record.getBondedAtoms()));
    }

    public Structure toDomainStructure(ParsedPdb parsed) {
        List<Atom> atoms = new ArrayList<>();
        for (AtomRecord record : parsed.getAtoms()) {
            atoms.add(toDomainAtom(record));
        }

        List<Bond> bonds = new ArrayList<>();
        for (BondRecord record : parsed.getBonds()) {
            bonds.add(toDomainBond(record));
        }

        List<Residue> residues = groupResidues(atoms);
        List<Chain> chains = groupChains(residues);

        return new Structure(
                parsed.getPdbId(),
                parsed.getTitle(),
                atoms,
                bonds,
                chains,
                residues.size(),
                toDomainValidation(parsed.getValidation())
        );
    }

    private List<Residue> groupResidues(List<Atom> atoms) {
        Map<String, List<Atom>> residueMap = new LinkedHashMap<>();
        for (Atom atom : atoms) {
            String key = atom.getChainId() + ":" + atom.getResidueSeqNumber() + ":" + atom.getICode();
            residueMap.computeIfAbsent(key, k -> new ArrayList<>()).add(atom);
        }

        List<Residue> residues = new ArrayList<>();
        for (Map.Entry<String, List<Atom>> entry : residueMap.entrySet()) {
            List<Atom> residueAtoms = entry.getValue();
            Atom first = residueAtoms.get(0);
            residues.add(new Residue(
                    first.getResidueName(),
                    first.getChainId(),
                    first.getResidueSeqNumber(),
                    first.getICode(),
                    residueAtoms
            ));
        }
        return residues;
    }

    private List<Chain> groupChains(List<Residue> residues) {
        Map<String, List<Residue>> chainMap = new LinkedHashMap<>();
        for (Residue residue : residues) {
            chainMap.computeIfAbsent(residue.getChainId(), k -> new ArrayList<>()).add(residue);
        }

        List<Chain> chains = new ArrayList<>();
        for (Map.Entry<String, List<Residue>> entry : chainMap.entrySet()) {
            chains.add(new Chain(entry.getKey(), entry.getValue()));
        }
        return chains;
    }

    public ValidationResult toDomainValidation(com.proteinviewer.model.ValidationResult vr) {
        List<ValidationWarning> warnings = new ArrayList<>();
        for (com.proteinviewer.model.ValidationWarning w : vr.getWarnings()) {
            warnings.add(new ValidationWarning(
                    w.getLineNumber(),
                    w.getField(),
                    w.getMessage(),
                    w.getSeverity()
            ));
        }
        return new ValidationResult(vr.isValid(), warnings);
    }
}
