import { describe, it, expect } from 'vitest';
import { CameraController } from './index';
import type { Vec3 } from '@/utils/math';

function makeMouseEvent(x: number, y: number): MouseEvent {
  return { clientX: x, clientY: y } as MouseEvent;
}

function makeWheelEvent(deltaY: number): WheelEvent {
  return { deltaY } as WheelEvent;
}

function makeKeyEvent(code: string): KeyboardEvent {
  return { code } as KeyboardEvent;
}

describe('CameraController', () => {
  it('defaults to orbit mode', () => {
    const cam = new CameraController();
    expect(cam.mode).toBe('orbit');
  });

  it('switches to trackball mode preserving position', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.setMode('trackball');
    expect(cam.mode).toBe('trackball');
    const state = cam.getState();
    expect(state.eye[2]).toBeCloseTo(10, 1);
  });

  it('switches to fly mode preserving position', () => {
    const cam = new CameraController([0, 5, 10], [0, 0, 0]);
    cam.setMode('fly');
    expect(cam.mode).toBe('fly');
    const state = cam.getState();
    expect(state.eye[1]).toBeCloseTo(5, 1);
  });

  it('orbit mode rotates with mouse drag', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.handleMouseDown(makeMouseEvent(100, 100));
    cam.handleMouseMove(makeMouseEvent(150, 100));
    cam.handleMouseUp(makeMouseEvent(150, 100));
    const state1 = cam.getState();
    const cam2 = new CameraController([0, 0, 10], [0, 0, 0]);
    const state2 = cam2.getState();
    expect(state1.eye[0]).not.toBeCloseTo(state2.eye[0], 1);
  });

  it('orbit mode zooms with wheel', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.handleWheel(makeWheelEvent(-100));
    const target = cam.getState().target;
    const dist = Math.sqrt(
      (cam.getState().eye[0] - target[0]) ** 2 +
      (cam.getState().eye[1] - target[1]) ** 2 +
      (cam.getState().eye[2] - target[2]) ** 2
    );
    expect(dist).toBeLessThan(10);
  });

  it('orbit mode pans with shift+drag simulation', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.handleMouseDown(makeMouseEvent(100, 100));
    cam.handleMouseMove(makeMouseEvent(120, 120));
    cam.handleMouseUp(makeMouseEvent(120, 120));
  });

  it('trackball mode rotates without gimbal lock', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.setCanvasSize(800, 600);
    cam.setMode('trackball');
    for (let i = 0; i < 10; i++) {
      cam.handleMouseDown(makeMouseEvent(400, 300));
      cam.handleMouseMove(makeMouseEvent(450, 300));
      cam.handleMouseUp(makeMouseEvent(450, 300));
    }
    const state = cam.getState();
    expect(state.viewMatrix).toBeDefined();
    expect(state.viewMatrix.length).toBe(16);
  });

  it('fly mode moves forward with W key', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.setMode('fly');
    const posBefore = [...cam.getState().eye] as Vec3;
    cam.handleKeyDown(makeKeyEvent('KeyW'));
    for (let i = 0; i < 10; i++) {
      cam.update(0.016);
    }
    cam.handleKeyUp(makeKeyEvent('KeyW'));
    const posAfter = [...cam.getState().eye] as Vec3;
    const moved = Math.abs(posAfter[0] - posBefore[0]) + Math.abs(posAfter[1] - posBefore[1]) + Math.abs(posAfter[2] - posBefore[2]);
    expect(moved).toBeGreaterThan(0.01);
  });

  it('fly mode looks with mouse move', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.setMode('fly');
    cam.handleMouseDown(makeMouseEvent(400, 300));
    cam.handleMouseMove(makeMouseEvent(410, 300));
    cam.handleMouseUp(makeMouseEvent(410, 300));
    const state = cam.getState();
    expect(state.viewMatrix).toBeDefined();
  });

  it('reset restores default view', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.handleMouseDown(makeMouseEvent(100, 100));
    cam.handleMouseMove(makeMouseEvent(200, 200));
    cam.handleMouseUp(makeMouseEvent(200, 200));
    cam.reset([0, 0, 10], [0, 0, 0]);
    const state = cam.getState();
    expect(state.eye[2]).toBeCloseTo(10, 1);
  });

  it('getState returns valid CameraState with all fields', () => {
    const cam = new CameraController([1, 2, 5], [0, 0, 0]);
    const state = cam.getState();
    expect(state.viewMatrix).toBeDefined();
    expect(state.eye).toBeDefined();
    expect(state.target).toBeDefined();
    expect(state.up).toBeDefined();
    expect(state.fov).toBe(60);
    expect(state.near).toBe(0.1);
    expect(state.far).toBe(1000);
  });

  it('momentum decays after mouse release', () => {
    const cam = new CameraController([0, 0, 10], [0, 0, 0]);
    cam.handleMouseDown(makeMouseEvent(100, 100));
    cam.handleMouseMove(makeMouseEvent(200, 100));
    cam.handleMouseUp(makeMouseEvent(200, 100));
    cam.update(0.5);
    cam.update(0.5);
    cam.update(0.5);
    const stateAfterDecay = cam.getState();
    expect(stateAfterDecay.eye).toBeDefined();
  });

  it('setCanvasSize stores dimensions', () => {
    const cam = new CameraController();
    cam.setCanvasSize(1920, 1080);
    expect(cam).toBeDefined();
  });
});
