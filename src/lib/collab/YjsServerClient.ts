import { RoomInfo, CollabUser } from './types';

export class YjsServerClient {
  private baseUrl: string;

  constructor(baseUrl?: string) {
    this.baseUrl = baseUrl || process.env.YJS_WS_SERVER_URL || 'http://localhost:1235';
  }

  async getHealth(): Promise<{ status: string; timestamp: string }> {
    const response = await fetch(`${this.baseUrl}/health`);
    if (!response.ok) {
      throw new Error(`Health check failed: ${response.status}`);
    }
    return response.json();
  }

  async getAllRooms(): Promise<{ rooms: RoomInfo[]; total: number }> {
    const response = await fetch(`${this.baseUrl}/rooms`);
    if (!response.ok) {
      throw new Error(`Failed to get rooms: ${response.status}`);
    }
    return response.json();
  }

  async getRoomInfo(documentId: string): Promise<RoomInfo | null> {
    const response = await fetch(`${this.baseUrl}/rooms/${documentId}`);
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      throw new Error(`Failed to get room info: ${response.status}`);
    }
    return response.json();
  }

  async closeRoom(documentId: string): Promise<{ success: boolean; message: string }> {
    const response = await fetch(`${this.baseUrl}/rooms/${documentId}`, {
      method: 'DELETE',
    });
    if (!response.ok) {
      throw new Error(`Failed to close room: ${response.status}`);
    }
    return response.json();
  }

  async getOnlineUsers(
    documentId: string
  ): Promise<{ documentId: string; users: CollabUser[]; count: number }> {
    const response = await fetch(`${this.baseUrl}/rooms/${documentId}/users`);
    if (!response.ok) {
      throw new Error(`Failed to get online users: ${response.status}`);
    }
    return response.json();
  }

  getWebSocketUrl(documentId: string, params: Record<string, string> = {}): string {
    const wsBaseUrl = this.baseUrl.replace('http://', 'ws://').replace('https://', 'wss://');
    const searchParams = new URLSearchParams(params);
    searchParams.set('documentId', documentId);
    return `${wsBaseUrl}?${searchParams.toString()}`;
  }
}

export const yjsServerClient = new YjsServerClient();
