import {
  type Vec3,
  type Mat4,
  vec3Copy,
  vec3Create,
  vec3Scale,
  vec3Add,
  degToRad,
  mat4LookAt,
} from '@/utils/math';

export class FlyCamera {
  position: Vec3;
  yaw: number;
  pitch: number;
  velocity: Vec3;
  private _maxPitch = degToRad(89);

  constructor(position: Vec3 = [0, 0, 5], yaw: number = -90, pitch: number = 0) {
    this.position = vec3Copy(position);
    this.yaw = degToRad(yaw);
    this.pitch = degToRad(pitch);
    this.velocity = vec3Create(0, 0, 0);
  }

  moveForward(speed: number): void {
    const forward = this._getForward();
    this.velocity[0] += forward[0] * speed;
    this.velocity[1] += forward[1] * speed;
    this.velocity[2] += forward[2] * speed;
  }

  moveRight(speed: number): void {
    const right = this._getRight();
    this.velocity[0] += right[0] * speed;
    this.velocity[1] += right[1] * speed;
    this.velocity[2] += right[2] * speed;
  }

  moveUp(speed: number): void {
    this.velocity[1] += speed;
  }

  look(dx: number, dy: number): void {
    this.yaw += dx;
    this.pitch = Math.max(-this._maxPitch, Math.min(this._maxPitch, this.pitch + dy));
  }

  getViewMatrix(): Mat4 {
    const forward = this._getForward();
    const target: Vec3 = [
      this.position[0] + forward[0],
      this.position[1] + forward[1],
      this.position[2] + forward[2],
    ];
    return mat4LookAt(this.position, target, [0, 1, 0]);
  }

  update(dt: number): void {
    const scaledVel = vec3Scale(vec3Create(), this.velocity, dt);
    vec3Add(this.position, this.position, scaledVel);
    this.velocity[0] *= 0.88;
    this.velocity[1] *= 0.88;
    this.velocity[2] *= 0.88;
  }

  private _getForward(): Vec3 {
    return [
      Math.cos(this.pitch) * Math.sin(this.yaw),
      Math.sin(this.pitch),
      -Math.cos(this.pitch) * Math.cos(this.yaw),
    ];
  }

  private _getRight(): Vec3 {
    return [
      Math.cos(this.yaw),
      0,
      -Math.sin(this.yaw),
    ];
  }
}
