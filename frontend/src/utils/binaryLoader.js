const MAGIC = 0x50444233;
const VERSION = 1;
const HEADER_SIZE = 14;
const ATOM_RECORD_SIZE = 15;
const BOND_RECORD_SIZE = 5;

const CODE_TO_ELEMENT = [
  '', 'H', 'C', 'N', 'O', 'F', 'P', 'S', 'CL', 'BR', 'I', 'CA', 'FE', 'MG', 'ZN', 'NA', 'K'
];

export async function loadStructureBinary(id) {
  const response = await fetch(`/api/structures/${id}/binary`);
  if (!response.ok) {
    throw new Error(`Failed to load structure binary: ${response.status}`);
  }

  const arrayBuffer = await response.arrayBuffer();
  const atomsCount = parseInt(response.headers.get('X-Atoms-Count') || '0', 10);
  const bondsCount = parseInt(response.headers.get('X-Bonds-Count') || '0', 10);

  return parseStructureBinary(arrayBuffer, { atomsCount, bondsCount });
}

export function parseStructureBinary(arrayBuffer, metadata = {}) {
  const dataView = new DataView(arrayBuffer);
  let offset = 0;

  const magic = dataView.getUint32(offset, true);
  offset += 4;
  if (magic !== MAGIC) {
    throw new Error(`Invalid magic number: expected 0x${MAGIC.toString(16).toUpperCase()}, got 0x${magic.toString(16).toUpperCase()}`);
  }

  const version = dataView.getUint16(offset, true);
  offset += 2;
  if (version !== VERSION) {
    throw new Error(`Unsupported version: ${version}`);
  }

  const atomCount = dataView.getUint32(offset, true);
  offset += 4;
  const bondPairCount = dataView.getUint32(offset, true);
  offset += 4;

  const atoms = new Array(atomCount);
  for (let i = 0; i < atomCount; i++) {
    const serialNumber = dataView.getUint16(offset, true);
    offset += 2;
    const x = dataView.getFloat32(offset, true);
    offset += 4;
    const y = dataView.getFloat32(offset, true);
    offset += 4;
    const z = dataView.getFloat32(offset, true);
    offset += 4;
    const elementCode = dataView.getUint8(offset, true);
    offset += 1;
    const tempFactorFixed = dataView.getUint16(offset, true);
    offset += 2;
    const flags = dataView.getUint8(offset, true);
    offset += 1;

    atoms[i] = {
      serialNumber,
      x,
      y,
      z,
      element: CODE_TO_ELEMENT[elementCode] || '',
      tempFactor: tempFactorFixed / 100.0,
      isHetatm: (flags & 0x01) !== 0
    };
  }

  const bondMap = new Map();
  for (let i = 0; i < bondPairCount; i++) {
    const atom1 = dataView.getUint16(offset, true);
    offset += 2;
    const atom2 = dataView.getUint16(offset, true);
    offset += 2;
    const order = dataView.getUint8(offset, true);
    offset += 1;

    if (!bondMap.has(atom1)) {
      bondMap.set(atom1, []);
    }
    bondMap.get(atom1).push(atom2);
  }

  const bonds = [];
  bondMap.forEach((bondedAtoms, atomSerial) => {
    bonds.push({
      atomSerial,
      bondedAtoms
    });
  });

  return {
    structureId: metadata.structureId,
    atoms,
    bonds,
    totalAtoms: atomCount,
    atomsCount: metadata.atomsCount || atomCount,
    bondsCount: metadata.bondsCount || bonds.length
  };
}

export function estimateBinarySize(atomCount, bondCount = Math.floor(atomCount * 0.5)) {
  return HEADER_SIZE + atomCount * ATOM_RECORD_SIZE + bondCount * BOND_RECORD_SIZE;
}

export function estimateJsonSize(atomCount, bondCount = Math.floor(atomCount * 0.5)) {
  const bytesPerAtom = 350;
  const bytesPerBond = 100;
  return atomCount * bytesPerAtom + bondCount * bytesPerBond;
}
