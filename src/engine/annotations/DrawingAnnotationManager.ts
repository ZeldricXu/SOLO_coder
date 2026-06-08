import * as THREE from 'three';
import type { DrawingPrimitive, DrawingVertex, DrawingSession, DrawingSurfaceHit } from '@/types/drawing';
import { DEFAULT_DRAWING_COLORS } from '@/types/drawing';
import { generateId } from '@/utils/geometry';

export class DrawingAnnotationManager {
  private scene: THREE.Scene;
  private camera: THREE.PerspectiveCamera;
  private raycaster: THREE.Raycaster;
  private pointer: THREE.Vector2;
  private surfaceMeshes: Map<string, THREE.Mesh> = new Map();
  private drawingGroup: THREE.Group;
  private currentLine: THREE.Line | null = null;
  private currentPoints: THREE.Vector3[] = [];
  private session: DrawingSession = {
    active: false,
    color: DEFAULT_DRAWING_COLORS[0],
    lineWidth: 2,
    tool: 'freehand',
    currentPrimitive: null,
  };
  private onPrimitiveComplete?: (primitive: DrawingPrimitive) => void;

  constructor(scene: THREE.Scene, camera: THREE.PerspectiveCamera) {
    this.scene = scene;
    this.camera = camera;
    this.raycaster = new THREE.Raycaster();
    this.raycaster.params.Line = { threshold: 0.1 };
    this.pointer = new THREE.Vector2();

    this.drawingGroup = new THREE.Group();
    this.drawingGroup.name = 'drawing-annotations';
    this.drawingGroup.renderOrder = 999;
    this.scene.add(this.drawingGroup);
  }

  registerSurfaceMesh(mesh: THREE.Mesh, surfaceId: string): void {
    this.surfaceMeshes.set(surfaceId, mesh);
  }

  unregisterSurfaceMesh(surfaceId: string): void {
    this.surfaceMeshes.delete(surfaceId);
  }

  clearAllSurfaces(): void {
    this.surfaceMeshes.clear();
  }

  setSession(session: Partial<DrawingSession>): void {
    this.session = { ...this.session, ...session };
  }

  getSession(): DrawingSession {
    return { ...this.session };
  }

  setOnPrimitiveComplete(callback: (primitive: DrawingPrimitive) => void): void {
    this.onPrimitiveComplete = callback;
  }

  private updatePointerFromEvent(clientX: number, clientY: number, rect: DOMRect): void {
    this.pointer.x = ((clientX - rect.left) / rect.width) * 2 - 1;
    this.pointer.y = -((clientY - rect.top) / rect.height) * 2 + 1;
  }

  pickSurface(clientX: number, clientY: number, rect: DOMRect): DrawingSurfaceHit | null {
    if (
      clientX < rect.left ||
      clientX > rect.right ||
      clientY < rect.top ||
      clientY > rect.bottom
    ) {
      return null;
    }

    this.updatePointerFromEvent(clientX, clientY, rect);
    this.raycaster.setFromCamera(this.pointer, this.camera);

    const meshes = Array.from(this.surfaceMeshes.values());
    const intersects = this.raycaster.intersectObjects(meshes, true);

    if (intersects.length === 0) return null;

    const hit = intersects[0];
    const face = hit.face;
    let normal = { x: 0, y: 1, z: 0 };
    if (face) {
      normal = { x: face.normal.x, y: face.normal.y, z: face.normal.z };
    }

    let objectType: DrawingSurfaceHit['objectType'] = 'floor';
    if (hit.object.userData) {
      if (hit.object.userData.type === 'wall') objectType = 'wall';
      else if (hit.object.userData.type === 'ceiling') objectType = 'ceiling';
      else if (hit.object.userData.type === 'furniture') objectType = 'furniture';
      else if (hit.object.userData.type === 'opening') objectType = 'opening';
    }

    let surfaceId: string | undefined;
    for (const [id, mesh] of this.surfaceMeshes.entries()) {
      if (mesh === hit.object || mesh.children.includes(hit.object as THREE.Mesh)) {
        surfaceId = id;
        break;
      }
    }

    return {
      point: { x: hit.point.x, y: hit.point.y, z: hit.point.z },
      normal,
      distance: hit.distance,
      surfaceId,
      objectType,
    };
  }

  startDrawing(clientX: number, clientY: number, rect: DOMRect): DrawingVertex | null {
    const hit = this.pickSurface(clientX, clientY, rect);
    if (!hit) return null;

    this.session.active = true;
    this.currentPoints = [new THREE.Vector3(hit.point.x, hit.point.y, hit.point.z)];
    this.session.currentPrimitive = {
      id: generateId(),
      type: this.session.tool,
      vertices: [
        {
          position: hit.point,
          normal: hit.normal,
          surfaceId: hit.surfaceId,
        },
      ],
      color: this.session.color,
      lineWidth: this.session.lineWidth,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    this.createLineVisual();
    return this.session.currentPrimitive.vertices[0];
  }

  continueDrawing(clientX: number, clientY: number, rect: DOMRect): DrawingVertex | null {
    if (!this.session.active || !this.session.currentPrimitive) return null;

    const hit = this.pickSurface(clientX, clientY, rect);
    if (!hit) return null;

    const lastPoint = this.currentPoints[this.currentPoints.length - 1];
    const newPoint = new THREE.Vector3(hit.point.x, hit.point.y, hit.point.z);
    const dist = lastPoint.distanceTo(newPoint);

    if (dist < 0.02) return null;

    this.currentPoints.push(newPoint);
    const vertex: DrawingVertex = {
      position: hit.point,
      normal: hit.normal,
      surfaceId: hit.surfaceId,
    };
    this.session.currentPrimitive.vertices.push(vertex);
    this.session.currentPrimitive.updatedAt = Date.now();

    this.updateLineVisual();
    return vertex;
  }

  endDrawing(): DrawingPrimitive | null {
    if (!this.session.currentPrimitive) {
      this.resetDrawingState();
      return null;
    }

    if (this.session.currentPrimitive.vertices.length < 2) {
      this.resetDrawingState();
      return null;
    }

    const completed = { ...this.session.currentPrimitive };

    if (this.session.tool === 'arrow' && completed.vertices.length >= 2) {
      this.createArrowVisual(completed);
    }

    if (this.onPrimitiveComplete) {
      this.onPrimitiveComplete(completed);
    }

    this.resetDrawingState();
    return completed;
  }

  cancelDrawing(): void {
    this.resetDrawingState();
  }

  private resetDrawingState(): void {
    this.session.active = false;
    this.currentLine = null;
    this.currentPoints = [];
    this.session.currentPrimitive = null;
  }

  private createLineVisual(): void {
    if (this.currentLine) {
      this.drawingGroup.remove(this.currentLine);
      this.currentLine.geometry.dispose();
      (this.currentLine.material as THREE.Material).dispose();
    }

    const geometry = new THREE.BufferGeometry().setFromPoints(this.currentPoints);
    const material = new THREE.LineBasicMaterial({
      color: new THREE.Color(this.session.color),
      linewidth: this.session.lineWidth,
      transparent: true,
      opacity: 0.95,
      depthTest: false,
    });

    this.currentLine = new THREE.Line(geometry, material);
    this.currentLine.name = `active-drawing-${Date.now()}`;
    this.currentLine.renderOrder = 1000;
    this.drawingGroup.add(this.currentLine);
  }

  private updateLineVisual(): void {
    if (!this.currentLine) return;
    this.currentLine.geometry.dispose();
    this.currentLine.geometry = new THREE.BufferGeometry().setFromPoints(this.currentPoints);
  }

  private createArrowVisual(primitive: DrawingPrimitive): void {
    if (primitive.vertices.length < 2) return;

    const startIdx = primitive.vertices.length - 2;
    const endIdx = primitive.vertices.length - 1;
    const start = new THREE.Vector3(
      primitive.vertices[startIdx].position.x,
      primitive.vertices[startIdx].position.y,
      primitive.vertices[startIdx].position.z
    );
    const end = new THREE.Vector3(
      primitive.vertices[endIdx].position.x,
      primitive.vertices[endIdx].position.y,
      primitive.vertices[endIdx].position.z
    );

    const dir = new THREE.Vector3().subVectors(end, start).normalize();
    const arrowLength = Math.min(0.3, start.distanceTo(end) * 0.2);
    const arrowColor = new THREE.Color(primitive.color);

    const arrowHelper = new THREE.ArrowHelper(
      dir,
      start,
      start.distanceTo(end),
      arrowColor.getHex(),
      arrowLength,
      arrowLength * 0.5
    );
    arrowHelper.name = `arrow-${primitive.id}`;
    arrowHelper.renderOrder = 1000;
    this.drawingGroup.add(arrowHelper);
  }

  renderPrimitive(primitive: DrawingPrimitive): void {
    const points = primitive.vertices.map(
      (v) => new THREE.Vector3(v.position.x, v.position.y, v.position.z)
    );

    if (points.length < 2) return;

    if (primitive.type === 'arrow') {
      this.createArrowVisual(primitive);
      return;
    }

    const geometry = new THREE.BufferGeometry().setFromPoints(points);
    const material = new THREE.LineBasicMaterial({
      color: new THREE.Color(primitive.color),
      linewidth: primitive.lineWidth,
      transparent: true,
      opacity: 0.95,
      depthTest: false,
    });

    const line = new THREE.Line(geometry, material);
    line.name = `drawing-${primitive.id}`;
    line.renderOrder = 1000;
    line.userData = { primitiveId: primitive.id };
    this.drawingGroup.add(line);
  }

  removePrimitive(primitiveId: string): void {
    const toRemove: THREE.Object3D[] = [];
    this.drawingGroup.traverse((obj) => {
      if (obj.userData.primitiveId === primitiveId || obj.name === `arrow-${primitiveId}`) {
        toRemove.push(obj);
      }
    });
    toRemove.forEach((obj) => {
      this.drawingGroup.remove(obj);
      if ((obj as THREE.Line).geometry) (obj as THREE.Line).geometry.dispose();
      if ((obj as THREE.Line).material) {
        const mat = (obj as THREE.Line).material as THREE.Material | THREE.Material[];
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose());
        else mat.dispose();
      }
    });
  }

  clearAllDrawings(): void {
    while (this.drawingGroup.children.length > 0) {
      const obj = this.drawingGroup.children[0];
      this.drawingGroup.remove(obj);
      if ((obj as THREE.Line).geometry) (obj as THREE.Line).geometry.dispose();
      if ((obj as THREE.Line).material) {
        const mat = (obj as THREE.Line).material as THREE.Material | THREE.Material[];
        if (Array.isArray(mat)) mat.forEach((m) => m.dispose());
        else mat.dispose();
      }
    }
  }

  setDrawingsVisible(visible: boolean): void {
    this.drawingGroup.visible = visible;
  }

  dispose(): void {
    this.clearAllDrawings();
    this.scene.remove(this.drawingGroup);
  }
}
