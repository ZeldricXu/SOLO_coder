import {
  Mat4,
  Vec3,
  mat4Multiply,
  mat4Invert,
  vec3TransformMat4,
  vec3Subtract,
  vec3Normalize,
  vec3Dot,
} from '@/utils/math';

export interface PickResult {
  atomIndex: number;
  distance: number;
}

export function pickAtom(
  mouseX: number,
  mouseY: number,
  canvasWidth: number,
  canvasHeight: number,
  viewMatrix: Mat4,
  projMatrix: Mat4,
  atoms: { index: number; x: number; y: number; z: number; vdWRadius: number }[]
): PickResult | null {
  const ndcX = (2 * mouseX) / canvasWidth - 1;
  const ndcY = 1 - (2 * mouseY) / canvasHeight;

  const vp = mat4Multiply(projMatrix, viewMatrix);
  const invVP = mat4Invert(vp);

  const nearNDC: Vec3 = [ndcX, ndcY, -1];
  const farNDC: Vec3 = [ndcX, ndcY, 1];

  const nearWorld = vec3TransformMat4([0, 0, 0], nearNDC, invVP);
  const farWorld = vec3TransformMat4([0, 0, 0], farNDC, invVP);

  const rayOrigin: Vec3 = [nearWorld[0], nearWorld[1], nearWorld[2]];
  const rayDir = vec3Normalize([0, 0, 0], vec3Subtract([0, 0, 0], farWorld, nearWorld));

  let closestResult: PickResult | null = null;

  for (const atom of atoms) {
    const center: Vec3 = [atom.x, atom.y, atom.z];
    const oc = vec3Subtract([0, 0, 0], rayOrigin, center);
    const a = vec3Dot(rayDir, rayDir);
    const b = 2 * vec3Dot(oc, rayDir);
    const c = vec3Dot(oc, oc) - atom.vdWRadius * atom.vdWRadius;
    const discriminant = b * b - 4 * a * c;

    if (discriminant < 0) continue;

    const sqrtDisc = Math.sqrt(discriminant);
    let t = (-b - sqrtDisc) / (2 * a);
    if (t < 0) {
      t = (-b + sqrtDisc) / (2 * a);
    }
    if (t < 0) continue;

    if (closestResult === null || t < closestResult.distance) {
      closestResult = { atomIndex: atom.index, distance: t };
    }
  }

  return closestResult;
}
