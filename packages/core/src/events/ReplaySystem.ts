import {
  GameEvent,
  ReplaySession,
  GameStateSnapshot,
} from '../types';
import { generateId, deepClone, toJSON, fromJSON } from '../utils';
import { StateRebuilder } from './StateRebuilder';

export class ReplaySystem {
  private session: ReplaySession;
  private rebuilder: StateRebuilder;
  private currentState: unknown = {};
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(rebuilder?: StateRebuilder) {
    this.rebuilder = rebuilder ?? new StateRebuilder();
    this.session = this.createEmptySession();
  }

  private createEmptySession(): ReplaySession {
    return {
      id: generateId(),
      events: [],
      snapshots: [],
      startTime: Date.now(),
      metadata: {},
      currentEventIndex: -1,
      isPlaying: false,
      playbackSpeed: 1,
    };
  }

  load(events: GameEvent[], snapshots: GameStateSnapshot[] = [], metadata: Record<string, unknown> = {}): void {
    this.stopTimer();
    this.session = {
      id: generateId(),
      events: deepClone(events),
      snapshots: deepClone(snapshots),
      startTime: Date.now(),
      metadata,
      currentEventIndex: -1,
      isPlaying: false,
      playbackSpeed: this.session.playbackSpeed,
    };
    this.currentState = {};
  }

  play(onEvent?: (event: GameEvent, state: unknown) => void): void {
    if (this.session.isPlaying) return;
    if (this.isAtEnd()) {
      this.session.currentEventIndex = -1;
      this.currentState = {};
    }

    this.session.isPlaying = true;
    this.startTimer(onEvent);
  }

  private startTimer(onEvent?: (event: GameEvent, state: unknown) => void): void {
    this.stopTimer();
    const baseInterval = 1000;
    const interval = Math.max(16, baseInterval / this.session.playbackSpeed);

    this.timer = setInterval(() => {
      if (!this.session.isPlaying) return;

      const result = this.step(1);
      if (result.event && onEvent) {
        onEvent(result.event, result.state);
      }

      if (this.isAtEnd()) {
        this.pause();
      }
    }, interval);
  }

  private stopTimer(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  step(steps: number = 1): { event: GameEvent | null; state: unknown } {
    if (steps === 0) {
      return { event: null, state: deepClone(this.currentState) };
    }

    if (steps > 0) {
      return this.stepForward(steps);
    } else {
      return this.stepBackward(Math.abs(steps));
    }
  }

  private stepForward(steps: number): { event: GameEvent | null; state: unknown } {
    let lastEvent: GameEvent | null = null;
    const targetIndex = Math.min(this.session.currentEventIndex + steps, this.session.events.length - 1);

    while (this.session.currentEventIndex < targetIndex) {
      this.session.currentEventIndex++;
      const event = this.session.events[this.session.currentEventIndex];
      this.currentState = this.rebuilder.applyEvent(this.currentState, event);
      lastEvent = event;
    }

    return { event: lastEvent, state: deepClone(this.currentState) };
  }

  private stepBackward(steps: number): { event: GameEvent | null; state: unknown } {
    const newIndex = Math.max(-1, this.session.currentEventIndex - steps);
    return this.seekToIndex(newIndex);
  }

  seek(target: { turnNumber?: number; eventIndex?: number }): { event: GameEvent | null; state: unknown } {
    if (target.eventIndex !== undefined) {
      return this.seekToIndex(target.eventIndex);
    }

    if (target.turnNumber !== undefined) {
      const eventIndex = this.findEventIndexByTurn(target.turnNumber);
      if (eventIndex !== -1) {
        return this.seekToIndex(eventIndex);
      }
    }

    return { event: null, state: deepClone(this.currentState) };
  }

  private seekToIndex(index: number): { event: GameEvent | null; state: unknown } {
    const clampedIndex = Math.max(-1, Math.min(index, this.session.events.length - 1));

    const closestSnapshot = this.findClosestSnapshot(clampedIndex);
    if (closestSnapshot && closestSnapshot.eventIndex >= 0) {
      this.currentState = deepClone(closestSnapshot.state);
      this.session.currentEventIndex = closestSnapshot.eventIndex;
    } else if (clampedIndex < this.session.currentEventIndex) {
      this.currentState = {};
      this.session.currentEventIndex = -1;
    }

    while (this.session.currentEventIndex < clampedIndex) {
      this.session.currentEventIndex++;
      const event = this.session.events[this.session.currentEventIndex];
      this.currentState = this.rebuilder.applyEvent(this.currentState, event);
    }

    const currentEvent = this.session.currentEventIndex >= 0
      ? this.session.events[this.session.currentEventIndex]
      : null;

    return { event: currentEvent, state: deepClone(this.currentState) };
  }

  private findClosestSnapshot(eventIndex: number): GameStateSnapshot | null {
    if (this.session.snapshots.length === 0) return null;

    let closest: GameStateSnapshot | null = null;

    for (const snapshot of this.session.snapshots) {
      if (snapshot.eventIndex <= eventIndex) {
        if (!closest || snapshot.eventIndex > closest.eventIndex) {
          closest = snapshot;
        }
      }
    }

    return closest;
  }

  private findEventIndexByTurn(turnNumber: number): number {
    for (let i = 0; i < this.session.events.length; i++) {
      if (this.session.events[i].turnNumber >= turnNumber) {
        return i;
      }
    }
    return this.session.events.length - 1;
  }

  pause(): void {
    this.session.isPlaying = false;
    this.stopTimer();
  }

  resume(onEvent?: (event: GameEvent, state: unknown) => void): void {
    if (this.session.isPlaying || this.isAtEnd()) return;
    this.session.isPlaying = true;
    this.startTimer(onEvent);
  }

  setPlaybackSpeed(speed: number): void {
    this.session.playbackSpeed = Math.max(0.1, Math.min(10, speed));
    if (this.session.isPlaying) {
      this.pause();
      this.resume();
    }
  }

  getCurrentState(): unknown {
    return deepClone(this.currentState);
  }

  getCurrentEventIndex(): number {
    return this.session.currentEventIndex;
  }

  getTotalEvents(): number {
    return this.session.events.length;
  }

  isAtEnd(): boolean {
    return this.session.currentEventIndex >= this.session.events.length - 1
      && this.session.events.length > 0;
  }

  isAtStart(): boolean {
    return this.session.currentEventIndex <= -1;
  }

  isPlaying(): boolean {
    return this.session.isPlaying;
  }

  getPlaybackSpeed(): number {
    return this.session.playbackSpeed;
  }

  getSession(): ReplaySession {
    return deepClone(this.session);
  }

  getEvents(): GameEvent[] {
    return deepClone(this.session.events);
  }

  getSnapshots(): GameStateSnapshot[] {
    return deepClone(this.session.snapshots);
  }

  toJSON(): Record<string, unknown> {
    return {
      session: this.session,
      currentState: this.currentState,
    };
  }

  static fromJSON(data: Record<string, unknown>, rebuilder?: StateRebuilder): ReplaySystem {
    const system = new ReplaySystem(rebuilder);
    system.session = deepClone(data.session as ReplaySession);
    system.currentState = deepClone(data.currentState);
    system.session.isPlaying = false;
    return system;
  }

  serialize(): string {
    return toJSON(this.toJSON());
  }

  static deserialize(json: string, rebuilder?: StateRebuilder): ReplaySystem {
    const data = fromJSON<Record<string, unknown>>(json);
    return ReplaySystem.fromJSON(data, rebuilder);
  }

  destroy(): void {
    this.stopTimer();
    this.session.isPlaying = false;
  }
}
