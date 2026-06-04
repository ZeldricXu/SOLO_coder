import {
  type Vec3,
  type Vec4,
  type Mat4,
  vec3Copy,
  vec3Subtract,
  vec3Normalize,
  vec3Scale,
  vec3Add,
  vec3Create,
  vec4Create,
  quatNormalize,
  quatMultiply,
  quatFromAxisAngle,
  mat4FromQuat,
  mat4LookAt,
} from '@/utils/math';

export class TrackballCamera {
  eye: Vec3;
  target: Vec3;
  up: Vec3;
  rotation: Vec4;
  angularVelocity: Vec3;
  private _minDistance = 0.1;
  private _maxDistance = 500;

  constructor(eye: Vec3 = [0, 0, 5], target: Vec3 = [0, 0, 0], up: Vec3 = [0, 1, 0]) {
    this.eye = vec3Copy(eye);
    this.target = vec3Copy(target);
    this.up = vec3Copy(up);
    this.rotation = vec4Create(0, 0, 0, 1);
    this.angularVelocity = vec3Create(0, 0, 0);
  }

  rotate(dx: number, dy: number, canvasWidth: number, canvasHeight: number): void {
    const minDim = Math.min(canvasWidth, canvasHeight);
    if (minDim === 0) return;

    const ndx = (2 * dx) / minDim;
    const ndy = (2 * dy) / minDim;

    const r2 = ndx * ndx + ndy * ndy;
    if (r2 > 1.0) {
      const scale = 1.0 / Math.sqrt(r2);
      this._applyRotation(ndx * scale, ndy * scale, 0);
    } else {
      this._applyRotation(ndx, ndy, Math.sqrt(1.0 - r2));
    }

    this.angularVelocity[0] += ndy * 2;
    this.angularVelocity[1] -= ndx * 2;
  }

  zoom(delta: number): void {
    const dir = vec3Subtract(vec3Create(), this.eye, this.target);
    const dist = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
    if (dist === 0) return;
    const newDist = Math.max(this._minDistance, Math.min(this._maxDistance, dist + delta));
    const scale = newDist / dist;
    this.eye[0] = this.target[0] + dir[0] * scale;
    this.eye[1] = this.target[1] + dir[1] * scale;
    this.eye[2] = this.target[2] + dir[2] * scale;
  }

  pan(dx: number, dy: number): void {
    const view = this.getViewMatrix();
    const right: Vec3 = [view[0], view[4], view[8]];
    const upVec: Vec3 = [view[1], view[5], view[9]];

    const panRight = vec3Scale(vec3Create(), right, dx);
    const panUpVec = vec3Scale(vec3Create(), upVec, dy);
    const panOffset = vec3Add(vec3Create(), panRight, panUpVec);

    vec3Add(this.eye, this.eye, panOffset);
    vec3Add(this.target, this.target, panOffset);
  }

  getViewMatrix(): Mat4 {
    const rotMat = mat4FromQuat(this.rotation);
    const offset = vec3Subtract(vec3Create(), this.eye, this.target);
    const rotatedOffset: Vec3 = [
      offset[0] * rotMat[0] + offset[1] * rotMat[4] + offset[2] * rotMat[8],
      offset[0] * rotMat[1] + offset[1] * rotMat[5] + offset[2] * rotMat[9],
      offset[0] * rotMat[2] + offset[1] * rotMat[6] + offset[2] * rotMat[10],
    ];
    const rotatedEye: Vec3 = [
      this.target[0] + rotatedOffset[0],
      this.target[1] + rotatedOffset[1],
      this.target[2] + rotatedOffset[2],
    ];
    const rotatedUp: Vec3 = [
      this.up[0] * rotMat[0] + this.up[1] * rotMat[4] + this.up[2] * rotMat[8],
      this.up[0] * rotMat[1] + this.up[1] * rotMat[5] + this.up[2] * rotMat[9],
      this.up[0] * rotMat[2] + this.up[1] * rotMat[6] + this.up[2] * rotMat[10],
    ];
    return mat4LookAt(rotatedEye, this.target, rotatedUp);
  }

  update(_dt: number): void {
    const speed = Math.sqrt(
      this.angularVelocity[0] * this.angularVelocity[0] +
      this.angularVelocity[1] * this.angularVelocity[1] +
      this.angularVelocity[2] * this.angularVelocity[2],
    );
    if (speed > 0.0001) {
      const axis = vec3Normalize(vec3Create(), vec3Copy(this.angularVelocity));
      const angle = speed * _dt;
      const dq = vec4Create(0, 0, 0, 1);
      quatFromAxisAngle(dq, axis, angle);
      const newRot = vec4Create(0, 0, 0, 1);
      quatMultiply(newRot, dq, this.rotation);
      quatNormalize(this.rotation, newRot);

      this.angularVelocity[0] *= 0.92;
      this.angularVelocity[1] *= 0.92;
      this.angularVelocity[2] *= 0.92;
    }
  }

  private _applyRotation(nx: number, ny: number, _nz: number): void {
    const view = this.getViewMatrix();
    const xAxis: Vec3 = [view[0], view[4], view[8]];
    const yAxis: Vec3 = [view[1], view[5], view[9]];
    vec3Normalize(xAxis, xAxis);
    vec3Normalize(yAxis, yAxis);

    const rotX = vec4Create(0, 0, 0, 1);
    const rotY = vec4Create(0, 0, 0, 1);
    quatFromAxisAngle(rotX, xAxis, ny * Math.PI);
    quatFromAxisAngle(rotY, yAxis, nx * Math.PI);

    const combined = vec4Create(0, 0, 0, 1);
    quatMultiply(combined, rotX, rotY);
    const newRot = vec4Create(0, 0, 0, 1);
    quatMultiply(newRot, combined, this.rotation);
    quatNormalize(this.rotation, newRot);
  }
}
