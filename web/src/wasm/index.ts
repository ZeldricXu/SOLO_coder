import type { Point, BoundingBox, Stroke, StrokeStyle, CRDTOperation, WASMBindings } from '../types';
import { CanvasFacade, SyncFacade, ExportFacade } from './facades';

class WasmModule implements WASMBindings {
  private initialized = false;
  private wasmInstance: any = null;
  public canvas: CanvasFacade | null = null;

  async init(): Promise<void> {
    if (this.initialized) return;

    try {
      const wasmPath = '/wasm/whiteboard_bg.wasm';
      const response = await fetch(wasmPath);

      if (!response.ok) {
        console.warn('WASM module not found, using fallback implementation');
        this.initialized = true;
        return;
      }

      const bytes = await response.arrayBuffer();
      const { instance } = await WebAssembly.instantiate(bytes, {});
      this.wasmInstance = instance.exports;
      this.initialized = true;
    } catch (error) {
      console.warn('Failed to load WASM, using fallback:', error);
      this.initialized = true;
    }
  }

  createStroke(points: Point[], style: StrokeStyle): Stroke {
    const bounds = this.computeBounds(points);
    return {
      id: crypto.randomUUID(),
      points,
      style,
      layerId: 'default',
      userId: 'local-user',
      createdAt: Date.now(),
      updatedAt: Date.now(),
      bounds,
    };
  }

  simplifyStroke(points: Point[], tolerance: number): Point[] {
    if (points.length <= 2) return points;

    const result: Point[] = [points[0]];

    for (let i = 1; i < points.length - 1; i++) {
      const prev = result[result.length - 1];
      const curr = points[i];
      const dist = Math.sqrt(Math.pow(curr.x - prev.x, 2) + Math.pow(curr.y - prev.y, 2));

      if (dist >= tolerance) {
        result.push(curr);
      }
    }

    result.push(points[points.length - 1]);
    return result;
  }

  intersects(a: BoundingBox, b: BoundingBox): boolean {
    return !(
      a.maxX < b.minX ||
      a.minX > b.maxX ||
      a.maxY < b.minY ||
      a.minY > b.maxY
    );
  }

  computeBounds(points: Point[]): BoundingBox {
    if (points.length === 0) {
      return { minX: 0, minY: 0, maxX: 0, maxY: 0 };
    }

    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;

    for (const point of points) {
      minX = Math.min(minX, point.x);
      minY = Math.min(minY, point.y);
      maxX = Math.max(maxX, point.x);
      maxY = Math.max(maxY, point.y);
    }

    return { minX, minY, maxX, maxY };
  }

  transformPoints(points: Point[], matrix: number[]): Point[] {
    const [a, b, c, d, tx, ty] = matrix;
    return points.map((p) => ({
      ...p,
      x: a * p.x + c * p.y + tx,
      y: b * p.x + d * p.y + ty,
    }));
  }

  applyCRDTOperation(_op: CRDTOperation): void {
    console.log('Applying CRDT operation:', _op);
  }

  mergeCRDTOperations(ops: CRDTOperation[]): CRDTOperation[] {
    return ops.sort((a, b) => a.timestamp - b.timestamp);
  }

  createCanvasFacade(width: number, height: number): CanvasFacade | null {
    if (!this.wasmInstance?.CanvasFacade) return null;
    try {
      const facade = new CanvasFacade();
      (facade as any).facade = new this.wasmInstance.CanvasFacade(width, height);
      return facade;
    } catch {
      return null;
    }
  }

  createSyncFacade(documentId: string, userId: string, username: string): SyncFacade | null {
    if (!this.wasmInstance?.SyncFacade) return null;
    try {
      const facade = new SyncFacade();
      (facade as any).facade = new this.wasmInstance.SyncFacade(documentId, userId, username);
      return facade;
    } catch {
      return null;
    }
  }

  createExportFacade(): ExportFacade | null {
    if (!this.wasmInstance?.ExportFacade) return null;
    try {
      const facade = new ExportFacade();
      (facade as any).facade = new this.wasmInstance.ExportFacade();
      return facade;
    } catch {
      return null;
    }
  }
}

export const wasm = new WasmModule();
export default wasm;
export { CanvasFacade, SyncFacade, ExportFacade };
