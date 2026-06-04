import type { Vec3, Mat4 } from '@/utils/math';
import { vec3Copy } from '@/utils/math';
import { OrbitCamera } from './orbit';
import { TrackballCamera } from './trackball';
import { FlyCamera } from './fly';
import type { Atom } from '../molecule-parser/types';

export type CameraMode = 'orbit' | 'trackball' | 'fly';

export interface ChainIsolationState {
  isActive: boolean;
  isolatedChainId: string | null;
  fadeOpacity: number;
}

export interface CameraState {
  viewMatrix: Mat4;
  eye: Vec3;
  target: Vec3;
  up: Vec3;
  fov: number;
  near: number;
  far: number;
}

export interface ChainBoundingBox {
  minX: number;
  maxX: number;
  minY: number;
  maxY: number;
  minZ: number;
  maxZ: number;
  center: Vec3;
  size: Vec3;
}

function computeChainBoundingBox(atoms: Atom[], chainId: string): ChainBoundingBox {
  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;

  for (const a of atoms) {
    if (a.chainId === chainId) {
      minX = Math.min(minX, a.x);
      minY = Math.min(minY, a.y);
      minZ = Math.min(minZ, a.z);
      maxX = Math.max(maxX, a.x);
      maxY = Math.max(maxY, a.y);
      maxZ = Math.max(maxZ, a.z);
    }
  }

  if (minX === Infinity) {
    return {
      minX: 0, maxX: 0, minY: 0, maxY: 0, minZ: 0, maxZ: 0,
      center: [0, 0, 0],
      size: [0, 0, 0],
    };
  }

  return {
    minX, maxX, minY, maxY, minZ, maxZ,
    center: [(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2],
    size: [maxX - minX, maxY - minY, maxZ - minZ],
  };
}

export class CameraController {
  mode: CameraMode = 'orbit';
  fov = 60;
  near = 0.1;
  far = 1000;
  private _orbit: OrbitCamera;
  private _trackball: TrackballCamera;
  private _fly: FlyCamera;
  private _isDragging = false;
  private _lastX = 0;
  private _lastY = 0;
  private _keys = new Set<string>();
  private _canvasWidth = 0;
  private _canvasHeight = 0;

  private _chainIsolation: ChainIsolationState = {
    isActive: false,
    isolatedChainId: null,
    fadeOpacity: 0.15,
  };

  constructor(eye: Vec3 = [0, 0, 5], target: Vec3 = [0, 0, 0], up: Vec3 = [0, 1, 0]) {
    this._orbit = new OrbitCamera(eye, target, up);
    this._trackball = new TrackballCamera(eye, target, up);
    this._fly = new FlyCamera(eye, -90, 0);
  }

  setMode(mode: CameraMode): void {
    if (mode === this.mode) return;
    const state = this.getState();
    this.mode = mode;
    this.reset(state.eye, state.target);
  }

  getChainIsolationState(): ChainIsolationState {
    return { ...this._chainIsolation };
  }

  setFadeOpacity(opacity: number): void {
    this._chainIsolation.fadeOpacity = Math.max(0, Math.min(1, opacity));
  }

  focusOnChain(
    atoms: Atom[],
    chainId: string,
    fovYDeg: number = 60,
    durationMs: number = 300
  ): ChainBoundingBox | null {
    const bbox = computeChainBoundingBox(atoms, chainId);
    if (bbox.size[0] === 0 && bbox.size[1] === 0 && bbox.size[2] === 0) {
      return null;
    }

    const maxDim = Math.max(bbox.size[0], bbox.size[1], bbox.size[2]);
    const halfFov = (fovYDeg * Math.PI / 180) / 2;
    const distance = (maxDim / 2) / Math.tan(halfFov) * 1.5;

    const state = this.getState();
    const dirToEye: Vec3 = [
      state.eye[0] - state.target[0],
      state.eye[1] - state.target[1],
      state.eye[2] - state.target[2],
    ];
    const dirLen = Math.sqrt(dirToEye[0] ** 2 + dirToEye[1] ** 2 + dirToEye[2] ** 2);
    if (dirLen > 0) {
      dirToEye[0] /= dirLen;
      dirToEye[1] /= dirLen;
      dirToEye[2] /= dirLen;
    } else {
      dirToEye[0] = 0;
      dirToEye[1] = 0;
      dirToEye[2] = 1;
    }

    const newEye: Vec3 = [
      bbox.center[0] + dirToEye[0] * distance,
      bbox.center[1] + dirToEye[1] * distance,
      bbox.center[2] + dirToEye[2] * distance,
    ];

    if (this.mode === 'orbit') {
      this._orbit.animateTo(newEye, bbox.center, durationMs);
    } else {
      this.reset(newEye, bbox.center);
    }

    this._chainIsolation.isActive = true;
    this._chainIsolation.isolatedChainId = chainId;

    return bbox;
  }

  exitChainIsolation(atoms: Atom[], durationMs: number = 300): void {
    if (!this._chainIsolation.isActive) return;

    this._chainIsolation.isActive = false;
    this._chainIsolation.isolatedChainId = null;

    if (atoms.length > 0) {
      let minX = Infinity, minY = Infinity, minZ = Infinity;
      let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
      for (const a of atoms) {
        minX = Math.min(minX, a.x);
        minY = Math.min(minY, a.y);
        minZ = Math.min(minZ, a.z);
        maxX = Math.max(maxX, a.x);
        maxY = Math.max(maxY, a.y);
        maxZ = Math.max(maxZ, a.z);
      }

      const center: Vec3 = [(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2];
      const maxDim = Math.max(maxX - minX, maxY - minY, maxZ - minZ);
      const distance = Math.max(5, (maxDim / 2) / Math.tan((this.fov * Math.PI / 180) / 2) * 1.5);

      const state = this.getState();
      const dirToEye: Vec3 = [
        state.eye[0] - state.target[0],
        state.eye[1] - state.target[1],
        state.eye[2] - state.target[2],
      ];
      const dirLen = Math.sqrt(dirToEye[0] ** 2 + dirToEye[1] ** 2 + dirToEye[2] ** 2);
      if (dirLen > 0) {
        dirToEye[0] /= dirLen;
        dirToEye[1] /= dirLen;
        dirToEye[2] /= dirLen;
      } else {
        dirToEye[0] = 0;
        dirToEye[1] = 0;
        dirToEye[2] = 1;
      }

      const newEye: Vec3 = [
        center[0] + dirToEye[0] * distance,
        center[1] + dirToEye[1] * distance,
        center[2] + dirToEye[2] * distance,
      ];

      if (this.mode === 'orbit') {
        this._orbit.animateTo(newEye, center, durationMs);
      } else {
        this.reset(newEye, center);
      }
    }
  }

  isAnimating(): boolean {
    if (this.mode === 'orbit') return this._orbit.isAnimating();
    return false;
  }

  cancelAnimation(): void {
    if (this.mode === 'orbit') this._orbit.cancelAnimation();
  }

  handleMouseDown(e: MouseEvent): void {
    if (this.isAnimating()) return;
    this._isDragging = true;
    this._lastX = e.clientX;
    this._lastY = e.clientY;
  }

  handleMouseMove(e: MouseEvent): void {
    if (!this._isDragging) return;
    if (this.isAnimating()) return;
    const dx = e.clientX - this._lastX;
    const dy = e.clientY - this._lastY;
    this._lastX = e.clientX;
    this._lastY = e.clientY;

    switch (this.mode) {
      case 'orbit':
        this._orbit.rotate(dx * 0.005, dy * 0.005);
        break;
      case 'trackball':
        this._trackball.rotate(dx, dy, this._canvasWidth, this._canvasHeight);
        break;
      case 'fly':
        this._fly.look(dx * 0.003, dy * 0.003);
        break;
    }
  }

  handleMouseUp(_e: MouseEvent): void {
    this._isDragging = false;
  }

  handleWheel(e: WheelEvent): void {
    if (this.isAnimating()) return;
    const delta = e.deltaY * 0.01;
    switch (this.mode) {
      case 'orbit':
        this._orbit.zoom(delta);
        break;
      case 'trackball':
        this._trackball.zoom(delta);
        break;
      case 'fly':
        this._fly.moveForward(-delta * 0.5);
        break;
    }
  }

  handleKeyDown(e: KeyboardEvent): void {
    this._keys.add(e.code);
    if (this.mode === 'fly' && !this.isAnimating()) {
      if (e.code === 'KeyW') this._fly.moveForward(1);
      if (e.code === 'KeyS') this._fly.moveForward(-1);
      if (e.code === 'KeyD') this._fly.moveRight(1);
      if (e.code === 'KeyA') this._fly.moveRight(-1);
      if (e.code === 'Space') this._fly.moveUp(1);
      if (e.code === 'ShiftLeft' || e.code === 'ShiftRight') this._fly.moveUp(-1);
    }
  }

  handleKeyUp(e: KeyboardEvent): void {
    this._keys.delete(e.code);
  }

  update(dt: number): void {
    switch (this.mode) {
      case 'orbit':
        this._orbit.update(dt);
        break;
      case 'trackball':
        this._trackball.update(dt);
        break;
      case 'fly':
        this._updateFlyKeys();
        this._fly.update(dt);
        break;
    }
  }

  getState(): CameraState {
    switch (this.mode) {
      case 'orbit':
        return {
          viewMatrix: this._orbit.getViewMatrix(),
          eye: vec3Copy(this._orbit.eye),
          target: vec3Copy(this._orbit.target),
          up: vec3Copy(this._orbit.up),
          fov: this.fov,
          near: this.near,
          far: this.far,
        };
      case 'trackball':
        return {
          viewMatrix: this._trackball.getViewMatrix(),
          eye: vec3Copy(this._trackball.eye),
          target: vec3Copy(this._trackball.target),
          up: vec3Copy(this._trackball.up),
          fov: this.fov,
          near: this.near,
          far: this.far,
        };
      case 'fly': {
        const view = this._fly.getViewMatrix();
        const forward: Vec3 = [-view[8], -view[9], -view[10]];
        const target: Vec3 = [
          this._fly.position[0] + forward[0],
          this._fly.position[1] + forward[1],
          this._fly.position[2] + forward[2],
        ];
        return {
          viewMatrix: view,
          eye: vec3Copy(this._fly.position),
          target,
          up: [0, 1, 0],
          fov: this.fov,
          near: this.near,
          far: this.far,
        };
      }
    }
  }

  reset(eye: Vec3, target: Vec3): void {
    switch (this.mode) {
      case 'orbit':
        this._orbit = new OrbitCamera(eye, target, [0, 1, 0]);
        break;
      case 'trackball':
        this._trackball = new TrackballCamera(eye, target, [0, 1, 0]);
        break;
      case 'fly':
        this._fly = new FlyCamera(eye, -90, 0);
        break;
    }
  }

  setCanvasSize(width: number, height: number): void {
    this._canvasWidth = width;
    this._canvasHeight = height;
  }

  private _updateFlyKeys(): void {
    if (this.isAnimating()) return;
    if (this._keys.has('KeyW')) this._fly.moveForward(0.1);
    if (this._keys.has('KeyS')) this._fly.moveForward(-0.1);
    if (this._keys.has('KeyD')) this._fly.moveRight(0.1);
    if (this._keys.has('KeyA')) this._fly.moveRight(-0.1);
    if (this._keys.has('Space')) this._fly.moveUp(0.1);
    if (this._keys.has('ShiftLeft') || this._keys.has('ShiftRight')) this._fly.moveUp(-0.1);
  }
}

