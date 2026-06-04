import { vec3Lerp, clamp } from '@/utils/math';
import { applyEasing, type EasingType } from './easing';

export interface AnimationState {
  name: string;
  positions: Float32Array;
}

export class AnimationEngine {
  private states: AnimationState[] = [];
  private currentTime: number = 0;
  private currentStateIndex: number = 0;
  private isPlaying: boolean = false;
  private easing: EasingType = 'linear';
  private speed: number = 1.0;
  private loop: boolean = false;

  addState(name: string, atoms: { x: number; y: number; z: number }[]): void {
    const positions = new Float32Array(atoms.length * 3);
    for (let i = 0; i < atoms.length; i++) {
      positions[i * 3] = atoms[i].x;
      positions[i * 3 + 1] = atoms[i].y;
      positions[i * 3 + 2] = atoms[i].z;
    }
    this.states.push({ name, positions });
  }

  removeState(index: number): void {
    this.states.splice(index, 1);
    if (this.currentStateIndex >= this.states.length) {
      this.currentStateIndex = Math.max(0, this.states.length - 1);
    }
  }

  setEasing(easing: EasingType): void {
    this.easing = easing;
  }

  setSpeed(speed: number): void {
    this.speed = speed;
  }

  setLoop(loop: boolean): void {
    this.loop = loop;
  }

  play(): void {
    this.isPlaying = true;
  }

  pause(): void {
    this.isPlaying = false;
  }

  seek(t: number): void {
    this.currentTime = clamp(t, 0, 1);
  }

  setCurrentState(index: number): void {
    this.currentStateIndex = clamp(index, 0, Math.max(0, this.states.length - 1));
  }

  update(dt: number): void {
    if (!this.isPlaying || this.states.length < 2) return;

    const duration = 1.0;
    this.currentTime += (dt * this.speed) / duration;

    if (this.currentTime >= 1.0) {
      if (this.loop) {
        this.currentTime -= 1.0;
        this.currentStateIndex = (this.currentStateIndex + 1) % this.states.length;
      } else {
        if (this.currentStateIndex < this.states.length - 2) {
          this.currentTime -= 1.0;
          this.currentStateIndex += 1;
        } else {
          this.currentTime = 1.0;
          this.isPlaying = false;
        }
      }
    }
  }

  getInterpolatedPositions(): Float32Array {
    if (this.states.length === 0) {
      return new Float32Array(0);
    }

    if (this.states.length === 1) {
      return this.states[0].positions;
    }

    const fromIndex = this.currentStateIndex;
    const toIndex = this.currentStateIndex + 1 < this.states.length
      ? this.currentStateIndex + 1
      : 0;

    const fromPositions = this.states[fromIndex].positions;
    const toPositions = this.states[toIndex].positions;
    const count = fromPositions.length;
    const result = new Float32Array(count);
    const easedT = applyEasing(this.currentTime, this.easing);
    const from: [number, number, number] = [0, 0, 0];
    const to: [number, number, number] = [0, 0, 0];
    const out: [number, number, number] = [0, 0, 0];

    for (let i = 0; i < count; i += 3) {
      from[0] = fromPositions[i];
      from[1] = fromPositions[i + 1];
      from[2] = fromPositions[i + 2];
      to[0] = toPositions[i];
      to[1] = toPositions[i + 1];
      to[2] = toPositions[i + 2];
      vec3Lerp(out, from, to, easedT);
      result[i] = out[0];
      result[i + 1] = out[1];
      result[i + 2] = out[2];
    }

    return result;
  }

  getStateCount(): number {
    return this.states.length;
  }

  getCurrentTime(): number {
    return this.currentTime;
  }

  getCurrentStateIndex(): number {
    return this.currentStateIndex;
  }

  getIsPlaying(): boolean {
    return this.isPlaying;
  }

  getStates(): AnimationState[] {
    return this.states;
  }
}

export type { EasingType };
export { applyEasing };
