import { ParsedMolecule } from './types';
import { parsePDB } from './pdb-parser';
import { parseSDF } from './sdf-parser';
import { parseXYZ } from './xyz-parser';
import type { Result } from './result';

export type { Atom, Bond, Model, FileMetadata, ParsedMolecule } from './types';
export type { Result, ParseError } from './result';
export { ParseErrorCode, ok, err, singleErr, createError } from './result';
export { getElementData, getCovalentRadius, getVdWRadius, getElementColor } from './elements';
export { parsePDB } from './pdb-parser';
export { parseSDF } from './sdf-parser';
export { parseXYZ } from './xyz-parser';

export function detectFormat(content: string, fileName: string): 'pdb' | 'sdf' | 'xyz' {
  const ext = fileName.toLowerCase().split('.').pop() ?? '';

  if (ext === 'pdb' || ext === 'ent') return 'pdb';
  if (ext === 'sdf' || ext === 'mol') return 'sdf';
  if (ext === 'xyz') return 'xyz';

  const trimmed = content.trim();

  if (/^(HEADER|ATOM|HETATM)\s/m.test(trimmed)) return 'pdb';
  if (trimmed.includes('$$$$') || /V[23]000\s*$/m.test(trimmed)) return 'sdf';
  if (/^\d+\s*$/m.test(trimmed.split('\n')[0] ?? '')) return 'xyz';

  return 'pdb';
}

export function parseMolecule(content: string, fileName: string): Result<ParsedMolecule> {
  const format = detectFormat(content, fileName);

  switch (format) {
    case 'pdb':
      return parsePDB(content, fileName);
    case 'sdf':
      return parseSDF(content, fileName);
    case 'xyz':
      return parseXYZ(content, fileName);
  }
}
