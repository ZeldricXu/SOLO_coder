import { Atom, Model, ParsedMolecule, FileMetadata } from './types';
import { getVdWRadius, getElementColor } from './elements';
import { Result, ok, createError, ParseErrorCode } from './result';
import type { ParseError } from './result';

export function parseXYZ(content: string, fileName: string): Result<ParsedMolecule> {
  const errors: ParseError[] = [];
  const lines = content.split('\n');

  if (content.trim().length === 0) {
    return ok({
      atoms: [],
      bonds: [],
      models: [],
      metadata: {
        format: 'xyz',
        fileName,
        atomCount: 0,
        bondCount: 0,
        modelCount: 0,
      },
    }, [createError(ParseErrorCode.EMPTY_CONTENT, 'Empty XYZ content', 0, 0, null)]);
  }

  if (lines.length < 3) {
    errors.push(createError(
      ParseErrorCode.MALFORMED_LINE,
      'XYZ file too short',
      lines.length,
      0,
      null
    ));
    return ok({
      atoms: [],
      bonds: [],
      models: [],
      metadata: {
        format: 'xyz',
        fileName,
        atomCount: 0,
        bondCount: 0,
        modelCount: 0,
      },
    }, errors);
  }

  const atomCount = parseInt(lines[0].trim(), 10);
  const titleLine = lines[1].trim();

  if (isNaN(atomCount) || atomCount <= 0) {
    errors.push(createError(
      ParseErrorCode.INVALID_FORMAT,
      `Invalid atom count: ${lines[0].trim()}`,
      1,
      0,
      null
    ));
    return ok({
      atoms: [],
      bonds: [],
      models: [],
      metadata: {
        format: 'xyz',
        fileName,
        atomCount: 0,
        bondCount: 0,
        modelCount: 0,
      },
    }, errors);
  }

  const atoms: Atom[] = [];

  for (let i = 0; i < atomCount && i + 2 < lines.length; i++) {
    const parts = lines[i + 2].trim().split(/\s+/);
    if (parts.length < 4) {
      errors.push(createError(
        ParseErrorCode.MALFORMED_LINE,
        `Atom line has too few fields: ${parts.length}`,
        i + 3,
        0,
        null
      ));
      continue;
    }

    const element = parts[0];
    const x = parseFloat(parts[1]);
    const y = parseFloat(parts[2]);
    const z = parseFloat(parts[3]);

    if (isNaN(x) || isNaN(y) || isNaN(z)) {
      errors.push(createError(
        ParseErrorCode.INVALID_COORDINATE,
        `Invalid coordinates: ${parts[1]} ${parts[2]} ${parts[3]}`,
        i + 3,
        0,
        null
      ));
      continue;
    }

    atoms.push({
      index: atoms.length,
      element,
      x,
      y,
      z,
      vdWRadius: getVdWRadius(element),
      color: getElementColor(element),
    });
  }

  if (atoms.length < atomCount) {
    errors.push(createError(
      ParseErrorCode.PARSING_FAILED,
      `Expected ${atomCount} atoms, parsed ${atoms.length}`,
      null,
      null,
      null
    ));
  }

  const model: Model = { index: 0, atoms, bonds: [] };

  const metadata: FileMetadata = {
    format: 'xyz',
    fileName,
    atomCount: atoms.length,
    bondCount: 0,
    modelCount: 1,
    title: titleLine || undefined,
  };

  return ok({ atoms, bonds: [], models: [model], metadata }, errors);
}
