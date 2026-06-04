import { Atom, Bond, Model, ParsedMolecule, FileMetadata } from './types';
import { getVdWRadius, getElementColor } from './elements';
import { Result, ok, createError, ParseErrorCode } from './result';
import type { ParseError } from './result';

const BACKBONE_NAMES = new Set(['N', 'CA', 'C', 'O']);

function deriveElement(elementField: string, atomName: string): string {
  if (elementField) {
    const cleaned = elementField.replace(/[^A-Za-z]/g, '');
    if (cleaned.length > 0) {
      return cleaned.charAt(0).toUpperCase() + cleaned.slice(1).toLowerCase();
    }
  }

  const trimmed = atomName.trim();
  const stripped = trimmed.replace(/^\d+/, '');
  if (stripped.length === 0) return 'C';

  if (stripped.length >= 2 && stripped[1] >= 'a' && stripped[1] <= 'z') {
    return stripped.charAt(0).toUpperCase() + stripped.charAt(1).toLowerCase();
  }

  return stripped.charAt(0).toUpperCase();
}

function parseIntSafe(s: string): number {
  const n = parseInt(s, 10);
  return isNaN(n) ? 0 : n;
}

function parseFloatSafe(s: string): number {
  const n = parseFloat(s);
  return isNaN(n) ? 0 : n;
}

export function parsePDB(content: string, fileName: string): Result<ParsedMolecule> {
  const errors: ParseError[] = [];
  const lines = content.split('\n');

  if (content.trim().length === 0) {
    return ok({
      atoms: [],
      bonds: [],
      models: [],
      metadata: {
        format: 'pdb',
        fileName,
        atomCount: 0,
        bondCount: 0,
        modelCount: 0,
      },
    }, [createError(ParseErrorCode.EMPTY_CONTENT, 'Empty PDB content', 0, 0, null)]);
  }

  interface ModelState {
    atoms: Atom[];
    serialToIndex: Map<number, number>;
  }

  const modelStates: ModelState[] = [];
  let currentAtoms: Atom[] = [];
  let currentSerialToIndex = new Map<number, number>();
  let hasModelRecords = false;

  let headerTitle = '';
  let title = '';
  let compound = '';
  const chains = new Set<string>();
  const conectPairs: [number, number][] = [];

  for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
    const line = lines[lineIdx];
    const recordType = line.substring(0, 6).trim();

    if (recordType === 'MODEL') {
      hasModelRecords = true;
      currentAtoms = [];
      currentSerialToIndex = new Map();
    } else if (recordType === 'ENDMDL') {
      modelStates.push({ atoms: currentAtoms, serialToIndex: currentSerialToIndex });
    } else if (recordType === 'ATOM' || recordType === 'HETATM') {
      if (line.length < 54) {
        errors.push(createError(
          ParseErrorCode.MALFORMED_LINE,
          `ATOM/HETATM line too short: ${line.length} chars`,
          lineIdx + 1,
          0,
          recordType
        ));
        continue;
      }

      const serial = parseIntSafe(line.substring(6, 11));
      const atomName = line.substring(12, 16);
      const residueName = line.substring(17, 20).trim();
      const chainId = line.substring(21, 22);
      const resSeq = parseIntSafe(line.substring(22, 26));
      const x = parseFloatSafe(line.substring(30, 38));
      const y = parseFloatSafe(line.substring(38, 46));
      const z = parseFloatSafe(line.substring(46, 54));

      if (x === 0 && y === 0 && z === 0 && line.substring(30, 54).trim() === '') {
        errors.push(createError(
          ParseErrorCode.INVALID_COORDINATE,
          'Missing or invalid coordinates',
          lineIdx + 1,
          30,
          recordType
        ));
      }

      const occupancy = line.length >= 60 ? parseFloat(line.substring(54, 60)) : NaN;
      const bFactor = line.length >= 66 ? parseFloat(line.substring(60, 66)) : NaN;
      const elementField = line.length >= 78 ? line.substring(76, 78).trim() : '';

      const element = deriveElement(elementField, atomName);
      const trimmedName = atomName.trim();

      const atomIndex = currentAtoms.length;
      const atom: Atom = {
        index: atomIndex,
        element,
        x,
        y,
        z,
        residue: residueName || undefined,
        residueSeq: resSeq || undefined,
        chainId: chainId.trim() || undefined,
        occupancy: isNaN(occupancy) ? undefined : occupancy,
        bFactor: isNaN(bFactor) ? undefined : bFactor,
        vdWRadius: getVdWRadius(element),
        color: getElementColor(element),
        isCA: trimmedName === 'CA' && element === 'C',
        isBackbone:
          BACKBONE_NAMES.has(trimmedName) &&
          (trimmedName === 'N' && element === 'N' ? true :
           trimmedName === 'CA' && element === 'C' ? true :
           trimmedName === 'C' && element === 'C' ? true :
           trimmedName === 'O' && element === 'O' ? true : false),
      };

      currentAtoms.push(atom);
      currentSerialToIndex.set(serial, atomIndex);

      const chain = chainId.trim();
      if (chain) chains.add(chain);
    } else if (recordType === 'CONECT') {
      if (line.length < 16) {
        errors.push(createError(
          ParseErrorCode.MALFORMED_LINE,
          'CONECT line too short',
          lineIdx + 1,
          0,
          recordType
        ));
        continue;
      }
      const s1 = parseIntSafe(line.substring(6, 11));
      const s2 = parseIntSafe(line.substring(11, 16));
      if (s1 > 0 && s2 > 0) {
        conectPairs.push([s1, s2]);
      }
      if (line.length >= 21) {
        const s3 = parseIntSafe(line.substring(16, 21));
        if (s3 > 0) conectPairs.push([s1, s3]);
      }
      if (line.length >= 26) {
        const s4 = parseIntSafe(line.substring(21, 26));
        if (s4 > 0) conectPairs.push([s1, s4]);
      }
    } else if (recordType === 'HEADER') {
      if (line.length >= 62) {
        headerTitle = line.substring(10, 62).trim();
      }
    } else if (recordType === 'TITLE') {
      if (line.length >= 10) {
        title += (title ? ' ' : '') + line.substring(10, 80).trim();
      }
    } else if (recordType === 'COMPND') {
      if (line.length >= 10) {
        compound += (compound ? ' ' : '') + line.substring(10, 80).trim();
      }
    }
  }

  if (!hasModelRecords && currentAtoms.length > 0) {
    modelStates.push({ atoms: currentAtoms, serialToIndex: currentSerialToIndex });
  }

  if (modelStates.length === 0) {
    errors.push(createError(
      ParseErrorCode.PARSING_FAILED,
      'No atoms found in PDB file',
      null,
      null,
      null
    ));
  }

  const models: Model[] = modelStates.map((state, i) => {
    const bonds: Bond[] = [];
    const seen = new Set<string>();
    for (const [s1, s2] of conectPairs) {
      const idx1 = state.serialToIndex.get(s1);
      const idx2 = state.serialToIndex.get(s2);
      if (idx1 !== undefined && idx2 !== undefined) {
        const key = Math.min(idx1, idx2) + ':' + Math.max(idx1, idx2);
        if (!seen.has(key)) {
          seen.add(key);
          bonds.push({ atomIndex1: idx1, atomIndex2: idx2, order: 1 });
        }
      }
    }
    return { index: i, atoms: state.atoms, bonds };
  });

  const atoms = models.length > 0 ? models[0].atoms : [];
  const bonds = models.length > 0 ? models[0].bonds : [];

  const metadata: FileMetadata = {
    format: 'pdb',
    fileName,
    atomCount: atoms.length,
    bondCount: bonds.length,
    modelCount: models.length,
    chains: chains.size > 0 ? Array.from(chains).sort() : undefined,
    title: (title || headerTitle) || undefined,
    compound: compound || undefined,
  };

  return ok({ atoms, bonds, models, metadata }, errors);
}
