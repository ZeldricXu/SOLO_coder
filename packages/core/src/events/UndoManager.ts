import {
  GameEvent,
  UndoStack,
  GameStateSnapshot,
} from '../types';
import { generateId, createChecksum, deepClone, toJSON, fromJSON } from '../utils';

const DEFAULT_MAX_SIZE = 100;

export class UndoManager {
  private stack: UndoStack;

  constructor(maxSize: number = DEFAULT_MAX_SIZE) {
    this.stack = {
      events: [],
      snapshots: [],
      currentIndex: -1,
      maxSize,
    };
  }

  pushState(state: unknown, events: GameEvent[] = []): void {
    if (this.stack.currentIndex < this.stack.snapshots.length - 1) {
      this.stack.snapshots = this.stack.snapshots.slice(0, this.stack.currentIndex + 1);
      this.stack.events = this.stack.events.slice(0, this.getLastEventIndex() + 1);
    }

    const stateStr = JSON.stringify(state);
    const snapshot: GameStateSnapshot = {
      id: generateId(),
      eventId: events.length > 0 ? events[events.length - 1].id : '',
      eventIndex: this.stack.events.length + events.length - 1,
      turnNumber: events.length > 0 ? events[events.length - 1].turnNumber : 0,
      state: deepClone(state),
      timestamp: Date.now(),
      checksum: createChecksum(stateStr),
    };

    this.stack.events.push(...deepClone(events));
    this.stack.snapshots.push(snapshot);
    this.stack.currentIndex = this.stack.snapshots.length - 1;

    this.enforceSizeLimit();
  }

  private getLastEventIndex(): number {
    if (this.stack.snapshots.length === 0) return -1;
    return this.stack.snapshots[this.stack.snapshots.length - 1].eventIndex;
  }

  private enforceSizeLimit(): void {
    while (this.stack.snapshots.length > this.stack.maxSize) {
      const removedSnapshot = this.stack.snapshots.shift()!;
      this.stack.currentIndex--;

      const nextSnapshot = this.stack.snapshots[0];
      if (nextSnapshot) {
        const eventsToRemove = nextSnapshot.eventIndex - removedSnapshot.eventIndex;
        if (eventsToRemove > 0) {
          this.stack.events.splice(0, eventsToRemove);
        }
      }

      this.stack.snapshots.forEach((s) => {
        s.eventIndex = Math.max(0, s.eventIndex - (nextSnapshot ? nextSnapshot.eventIndex : 0));
      });
    }
  }

  undo(steps: number = 1): { state: unknown; events: GameEvent[] } | null {
    if (!this.canUndo() || steps <= 0) return null;

    const actualSteps = Math.min(steps, this.stack.currentIndex + 1);
    const newIndex = this.stack.currentIndex - actualSteps;

    return this.goToIndex(newIndex);
  }

  redo(steps: number = 1): { state: unknown; events: GameEvent[] } | null {
    if (!this.canRedo() || steps <= 0) return null;

    const maxRedoSteps = this.stack.snapshots.length - 1 - this.stack.currentIndex;
    const actualSteps = Math.min(steps, maxRedoSteps);
    const newIndex = this.stack.currentIndex + actualSteps;

    return this.goToIndex(newIndex);
  }

  private goToIndex(newIndex: number): { state: unknown; events: GameEvent[] } | null {
    if (newIndex < -1 || newIndex >= this.stack.snapshots.length) return null;

    this.stack.currentIndex = newIndex;

    if (newIndex === -1) {
      return {
        state: {},
        events: [],
      };
    }

    const snapshot = this.stack.snapshots[newIndex];
    const prevSnapshot = newIndex > 0 ? this.stack.snapshots[newIndex - 1] : null;

    const startEventIdx = prevSnapshot ? prevSnapshot.eventIndex + 1 : 0;
    const endEventIdx = snapshot.eventIndex + 1;
    const appliedEvents = this.stack.events.slice(startEventIdx, endEventIdx);

    return {
      state: deepClone(snapshot.state),
      events: deepClone(appliedEvents),
    };
  }

  canUndo(): boolean {
    return this.stack.currentIndex >= 0;
  }

  canRedo(): boolean {
    return this.stack.currentIndex < this.stack.snapshots.length - 1;
  }

  getUndoCount(): number {
    return this.stack.currentIndex + 1;
  }

  getRedoCount(): number {
    return Math.max(0, this.stack.snapshots.length - 1 - this.stack.currentIndex);
  }

  getHistory(): Array<{
    snapshot: GameStateSnapshot;
    events: GameEvent[];
    isCurrent: boolean;
  }> {
    const history: Array<{
      snapshot: GameStateSnapshot;
      events: GameEvent[];
      isCurrent: boolean;
    }> = [];

    for (let i = 0; i < this.stack.snapshots.length; i++) {
      const snapshot = this.stack.snapshots[i];
      const prevSnapshot = i > 0 ? this.stack.snapshots[i - 1] : null;

      const startEventIdx = prevSnapshot ? prevSnapshot.eventIndex + 1 : 0;
      const endEventIdx = snapshot.eventIndex + 1;
      const stepEvents = this.stack.events.slice(startEventIdx, endEventIdx);

      history.push({
        snapshot: deepClone(snapshot),
        events: deepClone(stepEvents),
        isCurrent: i === this.stack.currentIndex,
      });
    }

    return history;
  }

  getCurrentSnapshot(): GameStateSnapshot | null {
    if (this.stack.currentIndex === -1) return null;
    return deepClone(this.stack.snapshots[this.stack.currentIndex]);
  }

  getCurrentState(): unknown {
    const snapshot = this.getCurrentSnapshot();
    return snapshot ? deepClone(snapshot.state) : {};
  }

  clearHistory(): void {
    this.stack = {
      events: [],
      snapshots: [],
      currentIndex: -1,
      maxSize: this.stack.maxSize,
    };
  }

  setUndoLimit(maxSize: number): void {
    this.stack.maxSize = Math.max(1, maxSize);
    this.enforceSizeLimit();
  }

  getUndoLimit(): number {
    return this.stack.maxSize;
  }

  getTotalSnapshots(): number {
    return this.stack.snapshots.length;
  }

  getTotalEvents(): number {
    return this.stack.events.length;
  }

  toJSON(): Record<string, unknown> {
    return deepClone({ stack: this.stack });
  }

  static fromJSON(data: Record<string, unknown>): UndoManager {
    const stackData = data.stack as UndoStack;
    const manager = new UndoManager(stackData.maxSize);
    manager.stack = deepClone(stackData);
    return manager;
  }

  serialize(): string {
    return toJSON(this.toJSON());
  }

  static deserialize(json: string): UndoManager {
    const data = fromJSON<Record<string, unknown>>(json);
    return UndoManager.fromJSON(data);
  }
}
