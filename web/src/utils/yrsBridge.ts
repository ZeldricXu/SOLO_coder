import * as Y from 'yjs';
import { Awareness, applyAwarenessUpdate, encodeAwarenessUpdate } from 'y-protocols/awareness';
import type { Stroke, Shape, Point, User, CRDTOperation, LayerType } from '../types';

type ShapeMapData = Omit<Shape, 'createdAt' | 'updatedAt'> & { createdAt?: number; updatedAt?: number };
type StrokeMapData = Omit<Stroke, 'createdAt' | 'updatedAt'> & { createdAt?: number; updatedAt?: number };

export interface YrsBridgeOptions {
  roomId: string;
  userId: string;
  userName: string;
  userColor: string;
  onShapeChange?: (shapes: Shape[]) => void;
  onStrokeChange?: (strokes: Stroke[]) => void;
  onUsersChange?: (users: User[]) => void;
  onCursorChange?: (userId: string, position: Point) => void;
  onOperation?: (operation: CRDTOperation) => void;
  onError?: (error: Error) => void;
}

export type CRDTKind = 'shape' | 'stroke' | 'text';

export class YrsBridge {
  private doc: Y.Doc;
  private awareness: Awareness;
  private shapesMap: Y.Map<Y.Map<unknown>>;
  private strokesMap: Y.Map<Y.Map<unknown>>;
  private textsMap: Y.Map<Y.XmlFragment>;
  private options: YrsBridgeOptions;
  private wsProvider: WebSocket | null = null;
  private connected = false;
  private observers: Set<() => void> = new Set();
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;

  constructor(options: YrsBridgeOptions) {
    this.options = options;
    this.doc = new Y.Doc();
    this.awareness = new Awareness(this.doc);

    this.shapesMap = this.doc.getMap<Y.Map<unknown>>('shapes');
    this.strokesMap = this.doc.getMap<Y.Map<unknown>>('strokes');
    this.textsMap = this.doc.getMap<Y.XmlFragment>('texts');

    this.setupAwareness();
    this.setupObservers();
  }

  private setupAwareness(): void {
    this.awareness.setLocalState({
      user: {
        id: this.options.userId,
        name: this.options.userName,
        color: this.options.userColor,
        isOnline: true,
        lastActive: Date.now(),
      },
      cursor: null,
    });

    this.awareness.on('change', () => {
      const users: User[] = [];
      const states = this.awareness.getStates();
      states.forEach((state) => {
        if (state?.user) {
          users.push({
            ...state.user,
            cursor: state.cursor,
          });
        }
      });
      this.options.onUsersChange?.(users);

      states.forEach((state) => {
        if (state?.cursor && state?.user?.id) {
          this.options.onCursorChange?.(state.user.id, state.cursor);
        }
      });
    });
  }

  private setupObservers(): void {
    const shapesObserver = () => {
      this.options.onShapeChange?.(this.getAllShapes());
    };
    this.shapesMap.observeDeep(shapesObserver);
    this.observers.add(() => this.shapesMap.unobserveDeep(shapesObserver));

    const strokesObserver = () => {
      this.options.onStrokeChange?.(this.getAllStrokes());
    };
    this.strokesMap.observeDeep(strokesObserver);
    this.observers.add(() => this.strokesMap.unobserveDeep(strokesObserver));
  }

  broadcastCursor(position: Point): void {
    const currentState = this.awareness.getLocalState();
    this.awareness.setLocalState({
      ...currentState,
      cursor: position,
      user: {
        ...currentState?.user,
        lastActive: Date.now(),
      },
    });
  }

  private createShapeMap(data: ShapeMapData): Y.Map<unknown> {
    const map = new Y.Map<unknown>();
    Object.entries(data).forEach(([key, value]) => {
      if (value !== undefined) {
        map.set(key, value);
      }
    });
    return map;
  }

  shapeToMap(shape: Shape): Y.Map<unknown> {
    return this.createShapeMap({
      id: shape.id,
      type: shape.type,
      x: shape.x,
      y: shape.y,
      width: shape.width,
      height: shape.height,
      rotation: shape.rotation,
      points: shape.points,
      style: shape.style,
      layerId: shape.layerId,
      userId: shape.userId,
      starConfig: shape.starConfig,
      arrowConfig: shape.arrowConfig,
      richTextConfig: shape.richTextConfig,
      createdAt: shape.createdAt,
      updatedAt: shape.updatedAt,
    });
  }

  mapToShape(map: Y.Map<unknown>): Shape {
    return {
      id: map.get('id') as string,
      type: map.get('type') as Shape['type'],
      x: map.get('x') as number,
      y: map.get('y') as number,
      width: map.get('width') as number,
      height: map.get('height') as number,
      rotation: map.get('rotation') as number | undefined,
      points: map.get('points') as Point[] | undefined,
      style: map.get('style') as Shape['style'],
      layerId: map.get('layerId') as string,
      userId: map.get('userId') as string,
      starConfig: map.get('starConfig') as Shape['starConfig'],
      arrowConfig: map.get('arrowConfig') as Shape['arrowConfig'],
      richTextConfig: map.get('richTextConfig') as Shape['richTextConfig'],
      createdAt: (map.get('createdAt') as number) ?? Date.now(),
      updatedAt: (map.get('updatedAt') as number) ?? Date.now(),
    };
  }

  private createStrokeMap(data: StrokeMapData): Y.Map<unknown> {
    const map = new Y.Map<unknown>();
    Object.entries(data).forEach(([key, value]) => {
      if (value !== undefined) {
        map.set(key, value);
      }
    });
    return map;
  }

  strokeToMap(stroke: Stroke): Y.Map<unknown> {
    return this.createStrokeMap({
      id: stroke.id,
      points: stroke.points,
      style: stroke.style,
      layerId: stroke.layerId,
      userId: stroke.userId,
      bounds: stroke.bounds,
      createdAt: stroke.createdAt,
      updatedAt: stroke.updatedAt,
    });
  }

  mapToStroke(map: Y.Map<unknown>): Stroke {
    return {
      id: map.get('id') as string,
      points: map.get('points') as Point[],
      style: map.get('style') as Stroke['style'],
      layerId: map.get('layerId') as string,
      userId: map.get('userId') as string,
      bounds: map.get('bounds') as Stroke['bounds'],
      createdAt: (map.get('createdAt') as number) ?? Date.now(),
      updatedAt: (map.get('updatedAt') as number) ?? Date.now(),
    };
  }

  addShape(shape: Shape): void {
    const shapeMap = this.shapeToMap(shape);
    this.shapesMap.set(shape.id, shapeMap);
    this.emitOperation('insert', shape.id, 'shape', shape as unknown as Record<string, unknown>);
  }

  updateShape(id: string, updates: Partial<Shape>): void {
    const shapeMap = this.shapesMap.get(id);
    if (!shapeMap) return;

    this.doc.transact(() => {
      Object.entries(updates).forEach(([key, value]) => {
        if (value !== undefined) {
          shapeMap.set(key, value as unknown);
        }
      });
      shapeMap.set('updatedAt', Date.now());
    });

    this.emitOperation('update', id, 'shape', updates);
  }

  removeShape(id: string): void {
    this.shapesMap.delete(id);
    this.emitOperation('delete', id, 'shape', {});
  }

  getShape(id: string): Shape | undefined {
    const map = this.shapesMap.get(id);
    return map ? this.mapToShape(map) : undefined;
  }

  getAllShapes(): Shape[] {
    const result: Shape[] = [];
    this.shapesMap.forEach((map) => {
      result.push(this.mapToShape(map));
    });
    return result;
  }

  addStroke(stroke: Stroke): void {
    const strokeMap = this.strokeToMap(stroke);
    this.strokesMap.set(stroke.id, strokeMap);
    this.emitOperation('insert', stroke.id, 'stroke', stroke as unknown as Record<string, unknown>);
  }

  updateStroke(id: string, updates: Partial<Stroke>): void {
    const strokeMap = this.strokesMap.get(id);
    if (!strokeMap) return;

    this.doc.transact(() => {
      Object.entries(updates).forEach(([key, value]) => {
        if (value !== undefined) {
          strokeMap.set(key, value as unknown);
        }
      });
      strokeMap.set('updatedAt', Date.now());
    });

    this.emitOperation('update', id, 'stroke', updates);
  }

  removeStroke(id: string): void {
    this.strokesMap.delete(id);
    this.emitOperation('delete', id, 'stroke', {});
  }

  getStroke(id: string): Stroke | undefined {
    const map = this.strokesMap.get(id);
    return map ? this.mapToStroke(map) : undefined;
  }

  getAllStrokes(): Stroke[] {
    const result: Stroke[] = [];
    this.strokesMap.forEach((map) => {
      result.push(this.mapToStroke(map));
    });
    return result;
  }

  getText(id: string): Y.XmlFragment | undefined {
    return this.textsMap.get(id);
  }

  createText(id: string): Y.XmlFragment {
    const fragment = new Y.XmlFragment();
    this.textsMap.set(id, fragment);
    return fragment;
  }

  removeText(id: string): void {
    this.textsMap.delete(id);
  }

  private emitOperation(type: CRDTOperation['type'], objectId: string, objectType: LayerType, payload: Record<string, unknown>): void {
    const operation: CRDTOperation = {
      id: `op-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
      type,
      userId: this.options.userId,
      boardId: this.options.roomId,
      objectId,
      objectType,
      payload,
      timestamp: Date.now(),
      vectorClock: { [this.options.userId]: Date.now() },
    };
    this.options.onOperation?.(operation);
  }

  async connect(wsUrl: string): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.wsProvider = new WebSocket(wsUrl);

        this.wsProvider.onopen = () => {
          this.connected = true;
          this.startHeartbeat();

          const helloMessage = {
            type: 'hello',
            payload: {
              roomId: this.options.roomId,
              userId: this.options.userId,
              userName: this.options.userName,
              userColor: this.options.userColor,
            },
          };
          this.wsProvider?.send(JSON.stringify(helloMessage));

          this.doc.on('update', (update: Uint8Array, origin: unknown) => {
            if (origin === this) return;
            if (this.wsProvider?.readyState === WebSocket.OPEN) {
              const message = {
                type: 'sync-update',
                payload: {
                  roomId: this.options.roomId,
                  update: Array.from(update),
                },
              };
              this.wsProvider.send(JSON.stringify(message));
            }
          });

          this.awareness.on('update', ({ added, updated, removed }: { added: number[]; updated: number[]; removed: number[] }, origin: unknown) => {
            if (origin === this) return;
            const changedClients = added.concat(updated).concat(removed);
            const awarenessUpdate = encodeAwarenessUpdate(this.awareness, changedClients);
            if (this.wsProvider?.readyState === WebSocket.OPEN) {
              const message = {
                type: 'awareness',
                payload: {
                  roomId: this.options.roomId,
                  update: Array.from(awarenessUpdate),
                },
              };
              this.wsProvider.send(JSON.stringify(message));
            }
          });

          resolve();
        };

        this.wsProvider.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data);
            this.handleMessage(message);
          } catch (error) {
            console.error('[YrsBridge] Failed to parse message:', error);
            this.options.onError?.(error instanceof Error ? error : new Error('Message parse error'));
          }
        };

        this.wsProvider.onerror = (event) => {
          const error = new Error(`WebSocket error: ${event.type}`);
          this.options.onError?.(error);
          reject(error);
        };

        this.wsProvider.onclose = () => {
          this.connected = false;
          this.stopHeartbeat();
        };
      } catch (error) {
        reject(error instanceof Error ? error : new Error('Connect failed'));
      }
    });
  }

  private handleMessage(message: Record<string, unknown>): void {
    const type = message.type as string;

    switch (type) {
      case 'sync-step-1': {
        const stateVector = new Uint8Array(message.stateVector as number[]);
        const update = Y.encodeStateAsUpdate(this.doc, stateVector);
        if (this.wsProvider?.readyState === WebSocket.OPEN) {
          this.wsProvider.send(JSON.stringify({
            type: 'sync-step-2',
            payload: {
              roomId: this.options.roomId,
              update: Array.from(update),
            },
          }));
        }
        break;
      }
      case 'sync-update': {
        const update = new Uint8Array(message.update as number[]);
        Y.applyUpdate(this.doc, update, this);
        break;
      }
      case 'awareness': {
        const update = new Uint8Array(message.update as number[]);
        applyAwarenessUpdate(this.awareness, update, this);
        break;
      }
      case 'init-sync': {
        const update = new Uint8Array(message.update as number[]);
        Y.applyUpdate(this.doc, update, this);

        const stateVector = Y.encodeStateVector(this.doc);
        if (this.wsProvider?.readyState === WebSocket.OPEN) {
          this.wsProvider.send(JSON.stringify({
            type: 'sync-step-1',
            payload: {
              roomId: this.options.roomId,
              stateVector: Array.from(stateVector),
            },
          }));
        }
        break;
      }
    }
  }

  private startHeartbeat(): void {
    this.heartbeatInterval = setInterval(() => {
      if (this.wsProvider?.readyState === WebSocket.OPEN) {
        this.wsProvider.send(JSON.stringify({ type: 'ping', timestamp: Date.now() }));
      }
    }, 30000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  disconnect(): void {
    this.observers.forEach((cleanup) => cleanup());
    this.observers.clear();

    this.stopHeartbeat();
    this.awareness.destroy();

    if (this.wsProvider) {
      if (this.wsProvider.readyState === WebSocket.OPEN) {
        this.wsProvider.send(JSON.stringify({
          type: 'leave',
          payload: {
            roomId: this.options.roomId,
            userId: this.options.userId,
          },
        }));
      }
      this.wsProvider.close();
      this.wsProvider = null;
    }

    this.doc.destroy();
    this.connected = false;
  }

  isConnected(): boolean {
    return this.connected;
  }

  getDoc(): Y.Doc {
    return this.doc;
  }

  getAwareness(): Awareness {
    return this.awareness;
  }

  encodeStateAsUpdate(): Uint8Array {
    return Y.encodeStateAsUpdate(this.doc);
  }

  applyUpdate(update: Uint8Array): void {
    Y.applyUpdate(this.doc, update);
  }

  transact(fn: () => void): void {
    this.doc.transact(fn);
  }
}

export type { ShapeMapData, StrokeMapData };
export default YrsBridge;
