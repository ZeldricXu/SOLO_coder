import {
  type Vec3,
  type Mat4,
  vec3Copy,
  vec3Subtract,
  vec3Normalize,
  vec3Cross,
  vec3Scale,
  vec3Add,
  vec3Create,
  vec3Lerp,
  mat4LookAt,
  smoothstep01,
} from '@/utils/math';

export class OrbitCamera {
  eye: Vec3;
  target: Vec3;
  up: Vec3;
  distance: number;
  theta: number;
  phi: number;
  angularVelocity: [number, number] = [0, 0];
  private _minDistance = 0.1;
  private _maxDistance = 500;
  private _minPhi = 0.01;
  private _maxPhi = Math.PI - 0.01;

  private _animation: {
    active: boolean;
    duration: number;
    elapsed: number;
    startEye: Vec3;
    startTarget: Vec3;
    startDistance: number;
    startTheta: number;
    startPhi: number;
    endEye: Vec3;
    endTarget: Vec3;
    endDistance: number;
    endTheta: number;
    endPhi: number;
  } | null = null;

  constructor(eye: Vec3 = [0, 0, 5], target: Vec3 = [0, 0, 0], up: Vec3 = [0, 1, 0]) {
    this.eye = vec3Copy(eye);
    this.target = vec3Copy(target);
    this.up = vec3Copy(up);
    const diff = vec3Subtract(vec3Create(), this.eye, this.target);
    this.distance = Math.sqrt(diff[0] * diff[0] + diff[1] * diff[1] + diff[2] * diff[2]);
    this.theta = Math.atan2(diff[0], diff[2]);
    this.phi = Math.acos(Math.max(-1, Math.min(1, diff[1] / (this.distance || 1))));
  }

  rotate(dTheta: number, dPhi: number): void {
    if (this._animation?.active) return;
    this.angularVelocity[0] += dTheta;
    this.angularVelocity[1] += dPhi;
    this.theta += dTheta;
    this.phi = Math.max(this._minPhi, Math.min(this._maxPhi, this.phi + dPhi));
    this._updateEye();
  }

  zoom(delta: number): void {
    if (this._animation?.active) return;
    this.distance = Math.max(this._minDistance, Math.min(this._maxDistance, this.distance + delta));
    this._updateEye();
  }

  pan(dx: number, dy: number): void {
    if (this._animation?.active) return;
    const forward = vec3Subtract(vec3Create(), this.target, this.eye);
    vec3Normalize(forward, forward);
    const right = vec3Cross(vec3Create(), forward, this.up);
    vec3Normalize(right, right);
    const panUp = vec3Cross(vec3Create(), right, forward);

    const panRight = vec3Scale(vec3Create(), right, dx);
    const panUpVec = vec3Scale(vec3Create(), panUp, dy);
    const panOffset = vec3Add(vec3Create(), panRight, panUpVec);

    vec3Add(this.eye, this.eye, panOffset);
    vec3Add(this.target, this.target, panOffset);
  }

  animateTo(eye: Vec3, target: Vec3, durationMs: number = 300): void {
    const diff = vec3Subtract(vec3Create(), eye, target);
    const endDistance = Math.sqrt(diff[0] * diff[0] + diff[1] * diff[1] + diff[2] * diff[2]);
    const endTheta = Math.atan2(diff[0], diff[2]);
    const endPhi = Math.acos(Math.max(-1, Math.min(1, diff[1] / (endDistance || 1))));

    this._animation = {
      active: true,
      duration: durationMs / 1000,
      elapsed: 0,
      startEye: vec3Copy(this.eye),
      startTarget: vec3Copy(this.target),
      startDistance: this.distance,
      startTheta: this.theta,
      startPhi: this.phi,
      endEye: vec3Copy(eye),
      endTarget: vec3Copy(target),
      endDistance,
      endTheta,
      endPhi,
    };
  }

  cancelAnimation(): void {
    this._animation = null;
  }

  isAnimating(): boolean {
    return this._animation?.active ?? false;
  }

  getViewMatrix(): Mat4 {
    return mat4LookAt(this.eye, this.target, this.up);
  }

  update(dt: number): void {
    if (this._animation?.active) {
      this._animation.elapsed += dt;
      const t = Math.min(1, this._animation.elapsed / this._animation.duration);
      const smoothT = smoothstep01(t);

      const a = this._animation;
      this.target = vec3Lerp(this.target, a.startTarget, a.endTarget, smoothT);
      this.distance = a.startDistance + (a.endDistance - a.startDistance) * smoothT;

      let dTheta = a.endTheta - a.startTheta;
      if (dTheta > Math.PI) dTheta -= 2 * Math.PI;
      if (dTheta < -Math.PI) dTheta += 2 * Math.PI;
      this.theta = a.startTheta + dTheta * smoothT;

      this.phi = a.startPhi + (a.endPhi - a.startPhi) * smoothT;
      this.phi = Math.max(this._minPhi, Math.min(this._maxPhi, this.phi));

      this._updateEye();

      if (t >= 1) {
        this._animation = null;
      }
      return;
    }

    if (Math.abs(this.angularVelocity[0]) > 0.0001 || Math.abs(this.angularVelocity[1]) > 0.0001) {
      this.theta += this.angularVelocity[0];
      this.phi = Math.max(this._minPhi, Math.min(this._maxPhi, this.phi + this.angularVelocity[1]));
      this.angularVelocity[0] *= 0.92;
      this.angularVelocity[1] *= 0.92;
      this._updateEye();
    }
  }

  private _updateEye(): void {
    const sinPhi = Math.sin(this.phi);
    this.eye[0] = this.target[0] + this.distance * sinPhi * Math.sin(this.theta);
    this.eye[1] = this.target[1] + this.distance * Math.cos(this.phi);
    this.eye[2] = this.target[2] + this.distance * sinPhi * Math.cos(this.theta);
  }
}

