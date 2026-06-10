import { Room, User, UserPresence, Operation, DEFAULT_CONFIG } from '../types';
import { createLogger } from '../utils/logger';

const logger = createLogger('RoomManager');

export class RoomManager {
  private rooms: Map<string, Room> = new Map();
  private maxOperationsPerRoom: number;

  constructor(maxOperationsPerRoom: number = DEFAULT_CONFIG.maxOperationsPerRoom) {
    this.maxOperationsPerRoom = maxOperationsPerRoom;
  }

  getOrCreateRoom(roomId: string): Room {
    let room = this.rooms.get(roomId);
    if (!room) {
      room = {
        id: roomId,
        users: new Map(),
        operations: [],
        lastSequence: 0,
        createdAt: Date.now()
      };
      this.rooms.set(roomId, room);
      logger.info('Created new room', { roomId });
    }
    return room;
  }

  getRoom(roomId: string): Room | undefined {
    return this.rooms.get(roomId);
  }

  hasRoom(roomId: string): boolean {
    return this.rooms.has(roomId);
  }

  deleteRoom(roomId: string): boolean {
    const deleted = this.rooms.delete(roomId);
    if (deleted) {
      logger.info('Deleted room', { roomId });
    }
    return deleted;
  }

  getRoomIds(): string[] {
    return Array.from(this.rooms.keys());
  }

  addUser(roomId: string, user: User): UserPresence {
    const room = this.getOrCreateRoom(roomId);
    const now = Date.now();
    const presence: UserPresence = {
      ...user,
      joinedAt: now,
      lastActive: now
    };
    room.users.set(user.id, presence);
    logger.info('User joined room', { roomId, userId: user.id, userName: user.name });
    return presence;
  }

  removeUser(roomId: string, userId: string): UserPresence | undefined {
    const room = this.getRoom(roomId);
    if (!room) {
      return undefined;
    }
    const presence = room.users.get(userId);
    if (presence) {
      room.users.delete(userId);
      logger.info('User left room', { roomId, userId });
      if (room.users.size === 0) {
        logger.info('Room is empty, scheduling cleanup', { roomId });
      }
    }
    return presence;
  }

  getUser(roomId: string, userId: string): UserPresence | undefined {
    return this.getRoom(roomId)?.users.get(userId);
  }

  getUsers(roomId: string): UserPresence[] {
    const room = this.getRoom(roomId);
    return room ? Array.from(room.users.values()) : [];
  }

  getUserIds(roomId: string): string[] {
    const room = this.getRoom(roomId);
    return room ? Array.from(room.users.keys()) : [];
  }

  hasUser(roomId: string, userId: string): boolean {
    return this.getRoom(roomId)?.users.has(userId) ?? false;
  }

  updateUserActivity(roomId: string, userId: string): boolean {
    const presence = this.getUser(roomId, userId);
    if (presence) {
      presence.lastActive = Date.now();
      return true;
    }
    return false;
  }

  getNextSequence(roomId: string): number {
    const room = this.getOrCreateRoom(roomId);
    room.lastSequence += 1;
    return room.lastSequence;
  }

  getCurrentSequence(roomId: string): number {
    return this.getRoom(roomId)?.lastSequence ?? 0;
  }

  addOperation(roomId: string, operation: Operation): void {
    const room = this.getOrCreateRoom(roomId);
    room.operations.push(operation);
    if (room.operations.length > this.maxOperationsPerRoom) {
      const toRemove = room.operations.length - this.maxOperationsPerRoom;
      room.operations.splice(0, toRemove);
    }
  }

  getOperations(roomId: string, fromSequence?: number): Operation[] {
    const room = this.getRoom(roomId);
    if (!room) {
      return [];
    }
    if (fromSequence === undefined) {
      return [...room.operations];
    }
    return room.operations.filter(op => op.sequence > fromSequence);
  }

  getRoomStats(roomId: string): { userCount: number; operationCount: number; lastSequence: number } | null {
    const room = this.getRoom(roomId);
    if (!room) {
      return null;
    }
    return {
      userCount: room.users.size,
      operationCount: room.operations.length,
      lastSequence: room.lastSequence
    };
  }

  getStats(): Record<string, { userCount: number; operationCount: number; lastSequence: number }> {
    const stats: Record<string, { userCount: number; operationCount: number; lastSequence: number }> = {};
    for (const [roomId, room] of this.rooms) {
      stats[roomId] = {
        userCount: room.users.size,
        operationCount: room.operations.length,
        lastSequence: room.lastSequence
      };
    }
    return stats;
  }

  cleanupEmptyRooms(): string[] {
    const removed: string[] = [];
    for (const [roomId, room] of this.rooms) {
      if (room.users.size === 0) {
        this.rooms.delete(roomId);
        removed.push(roomId);
        logger.info('Cleaned up empty room', { roomId });
      }
    }
    return removed;
  }
}
