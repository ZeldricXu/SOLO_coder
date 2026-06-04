import { Atom, Bond, Model, ParsedMolecule, FileMetadata } from './types';
import { getVdWRadius, getElementColor } from './elements';
import { Result, ok, createError, ParseErrorCode } from './result';
import type { ParseError } from './result';

function parseIntSafe(s: string): number {
  const n = parseInt(s, 10);
  return isNaN(n) ? 0 : n;
}

function parseFloatSafe(s: string): number {
  const n = parseFloat(s);
  return isNaN(n) ? 0 : n;
}

interface ParsedMolBlock {
  atoms: Atom[];
  bonds: Bond[];
  title: string;
}

function parseMolBlock(lines: string[], errors: ParseError[]): ParsedMolBlock | null {
  if (lines.length < 4) {
    errors.push(createError(
      ParseErrorCode.MALFORMED_LINE,
      'MOL block too short',
      1,
      0,
      null
    ));
    return null;
  }

  const countsLine = lines[3];
  const version = countsLine.length >= 39 ? countsLine.substring(33, 39).trim() : '';
  if (version !== 'V2000' && version !== '2000') {
    errors.push(createError(
      ParseErrorCode.INVALID_FORMAT,
      `Unsupported MOL version: ${version}`,
      4,
      33,
      null
    ));
  }

  const numAtoms = parseIntSafe(countsLine.substring(0, 3));
  const numBonds = parseIntSafe(countsLine.substring(3, 6));

  if (numAtoms === 0) {
    errors.push(createError(
      ParseErrorCode.MISSING_REQUIRED_FIELD,
      'No atoms declared in counts line',
      4,
      0,
      null
    ));
    return null;
  }

  const molTitle = lines[0].trim();
  const atoms: Atom[] = [];
  const bonds: Bond[] = [];

  for (let i = 0; i < numAtoms && (4 + i) < lines.length; i++) {
    const line = lines[4 + i];
    if (line.length < 34) {
      errors.push(createError(
        ParseErrorCode.MALFORMED_LINE,
        `Atom line too short: ${line.length} chars`,
        5 + i,
        0,
        null
      ));
      continue;
    }
    const x = parseFloatSafe(line.substring(0, 10));
    const y = parseFloatSafe(line.substring(10, 20));
    const z = parseFloatSafe(line.substring(20, 30));
    const element = line.substring(31, 34).trim();

    if (!element) {
      errors.push(createError(
        ParseErrorCode.MISSING_REQUIRED_FIELD,
        'Missing element symbol',
        5 + i,
        31,
        null
      ));
    }

    atoms.push({
      index: i,
      element,
      x,
      y,
      z,
      vdWRadius: getVdWRadius(element),
      color: getElementColor(element),
    });
  }

  for (let i = 0; i < numBonds && (4 + numAtoms + i) < lines.length; i++) {
    const line = lines[4 + numAtoms + i];
    if (line.length < 9) {
      errors.push(createError(
        ParseErrorCode.MALFORMED_LINE,
        'Bond line too short',
        5 + numAtoms + i,
        0,
        null
      ));
      continue;
    }
    const atom1 = parseIntSafe(line.substring(0, 3));
    const atom2 = parseIntSafe(line.substring(3, 6));
    const bondType = parseIntSafe(line.substring(6, 9));

    if (atom1 > 0 && atom2 > 0 && atom1 <= numAtoms && atom2 <= numAtoms) {
      bonds.push({
        atomIndex1: atom1 - 1,
        atomIndex2: atom2 - 1,
        order: bondType === 4 ? 1 : bondType,
      });
    } else {
      errors.push(createError(
        ParseErrorCode.INVALID_ATOM_INDEX,
        `Invalid atom index in bond: ${atom1}-${atom2}`,
        5 + numAtoms + i,
        0,
        null
      ));
    }
  }

  return { atoms, bonds, title: molTitle };
}

export function parseSDF(content: string, fileName: string): Result<ParsedMolecule> {
  const errors: ParseError[] = [];

  if (content.trim().length === 0) {
    return ok({
      atoms: [],
      bonds: [],
      models: [],
      metadata: {
        format: 'sdf',
        fileName,
        atomCount: 0,
        bondCount: 0,
        modelCount: 0,
      },
    }, [createError(ParseErrorCode.EMPTY_CONTENT, 'Empty SDF content', 0, 0, null)]);
  }

  const blocks = content.split('$$$$');
  const models: Model[] = [];
  let firstTitle = '';

  for (let blockIdx = 0; blockIdx < blocks.length; blockIdx++) {
    const block = blocks[blockIdx];
    const trimmed = block.trim();
    if (!trimmed) continue;

    const lines = trimmed.split('\n');
    const result = parseMolBlock(lines, errors);
    if (!result) continue;

    if (models.length === 0) {
      firstTitle = result.title;
    }

    models.push({
      index: models.length,
      atoms: result.atoms,
      bonds: result.bonds,
    });
  }

  if (models.length === 0) {
    errors.push(createError(
      ParseErrorCode.PARSING_FAILED,
      'No valid molecules found in SDF file',
      null,
      null,
      null
    ));
  }

  const atoms = models.length > 0 ? models[0].atoms : [];
  const bonds = models.length > 0 ? models[0].bonds : [];

  const metadata: FileMetadata = {
    format: 'sdf',
    fileName,
    atomCount: atoms.length,
    bondCount: bonds.length,
    modelCount: models.length,
    title: firstTitle || undefined,
  };

  return ok({ atoms, bonds, models, metadata }, errors);
}
