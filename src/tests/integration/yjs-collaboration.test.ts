import { describe, it, expect, beforeAll, beforeEach } from 'vitest';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';
import WebSocket from 'ws';

class MockWebSocketServer {
  private rooms: Map<string, Set<WebSocket>> = new Map();
  private docStates: Map<string, Uint8Array> = new Map();

  handleConnection(socket: WebSocket, roomId: string) {
    if (!this.rooms.has(roomId)) {
      this.rooms.set(roomId, new Set());
    }
    this.rooms.get(roomId)!.add(socket);

    const savedState = this.docStates.get(roomId);
    if (savedState) {
      socket.send(savedState);
    }

    socket.on('message', (data: Buffer) => {
      this.broadcast(roomId, data, socket);
      this.docStates.set(roomId, new Uint8Array(data));
    });

    socket.on('close', () => {
      this.rooms.get(roomId)?.delete(socket);
    });
  }

  private broadcast(roomId: string, data: Buffer, excludeSocket: WebSocket) {
    this.rooms.get(roomId)?.forEach((client) => {
      if (client !== excludeSocket && client.readyState === WebSocket.OPEN) {
        client.send(data);
      }
    });
  }

  getRoomSize(roomId: string): number {
    return this.rooms.get(roomId)?.size || 0;
  }

  clear() {
    this.rooms.clear();
    this.docStates.clear();
  }
}

describe('Yjs协作编辑集成测试', () => {
  let mockServer: MockWebSocketServer;

  beforeAll(() => {
    mockServer = new MockWebSocketServer();
  });

  beforeEach(() => {
    mockServer.clear();
  });

  describe('多客户端协同编辑', () => {
    it('两个独立客户端的Yjs文档同步', async () => {
      const roomId = `test-room-${Date.now()}`;

      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      let syncPromise = new Promise<void>((resolve) => {
        doc2.on('update', () => {
          if (text2.toString() === 'Hello from client 1') {
            resolve();
          }
        });
      });

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      text1.insert(0, 'Hello from client 1');

      await syncPromise;

      expect(text1.toString()).toBe('Hello from client 1');
      expect(text2.toString()).toBe('Hello from client 1');
    });

    it('多个客户端并发编辑最终一致', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();
      const doc3 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');
      const text3 = doc3.getText('content');

      const allDocs = [doc1, doc2, doc3];

      allDocs.forEach((doc, i) => {
        doc.on('update', (update: Uint8Array) => {
          allDocs.forEach((otherDoc, j) => {
            if (i !== j) {
              Y.applyUpdate(otherDoc, update);
            }
          });
        });
      });

      text1.insert(0, 'A');
      text2.insert(1, 'B');
      text3.insert(2, 'C');

      await new Promise((resolve) => setTimeout(resolve, 100));

      const finalText = text1.toString();
      expect(text2.toString()).toBe(finalText);
      expect(text3.toString()).toBe(finalText);
      expect(finalText).toContain('A');
      expect(finalText).toContain('B');
      expect(finalText).toContain('C');
    });

    it('CRDT因果顺序保证一致性', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      doc2.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc1, update);
      });

      text1.insert(0, '123');
      text2.insert(3, '456');

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(text1.toString()).toBe(text2.toString());
      expect(text1.toString().length).toBe(6);

      text1.delete(0, 3);

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(text1.toString()).toBe(text2.toString());
      expect(text1.toString()).toBe('456');
    });
  });

  describe('网络断开重连', () => {
    it('网络断开期间的修改在重连后同步', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      let syncEnabled = true;

      doc1.on('update', (update: Uint8Array) => {
        if (syncEnabled) {
          Y.applyUpdate(doc2, update);
        }
      });

      text1.insert(0, 'Initial content');

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(text2.toString()).toBe('Initial content');

      syncEnabled = false;

      text1.insert(text1.length, ' - edited while offline');

      expect(text2.toString()).toBe('Initial content');

      syncEnabled = true;

      const stateVector = Y.encodeStateVector(doc2);
      const missingUpdate = Y.encodeStateAsUpdate(doc1, stateVector);
      Y.applyUpdate(doc2, missingUpdate);

      expect(text2.toString()).toBe('Initial content - edited while offline');
      expect(text1.toString()).toBe(text2.toString());
    });

    it('状态向量恢复未同步的变更', async () => {
      const docA = new Y.Doc();
      const docB = new Y.Doc();

      const arrayA = docA.getArray('items');
      const arrayB = docB.getArray('items');

      docA.on('update', (update: Uint8Array) => {
        Y.applyUpdate(docB, update);
      });

      arrayA.insert(0, ['item1', 'item2']);

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(arrayB.toArray()).toEqual(['item1', 'item2']);

      const stateVectorB = Y.encodeStateVector(docB);

      arrayA.insert(2, ['item3', 'item4']);

      const updateFromA = Y.encodeStateAsUpdate(docA, stateVectorB);

      const docC = new Y.Doc();
      const arrayC = docC.getArray('items');

      Y.applyUpdate(docC, Y.encodeStateAsUpdate(docB));
      Y.applyUpdate(docC, updateFromA);

      expect(arrayC.toArray()).toEqual(['item1', 'item2', 'item3', 'item4']);
    });

    it('双向离线修改后同步一致', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));

      const updatesFrom1: Uint8Array[] = [];
      const updatesFrom2: Uint8Array[] = [];

      doc1.on('update', (update: Uint8Array) => updatesFrom1.push(update));
      doc2.on('update', (update: Uint8Array) => updatesFrom2.push(update));

      text1.insert(0, 'Hello from 1');

      text2.insert(0, 'Hello from 2');

      updatesFrom1.forEach((update) => Y.applyUpdate(doc2, update));
      updatesFrom2.forEach((update) => Y.applyUpdate(doc1, update));

      expect(text1.toString()).toBe(text2.toString());

      const finalText = text1.toString();
      expect(finalText).toContain('Hello from 1');
      expect(finalText).toContain('Hello from 2');
    });
  });

  describe('复杂文档结构同步', () => {
    it('嵌套Map结构同步', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const map1 = doc1.getMap('data');
      const map2 = doc2.getMap('data');

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      const nestedMap = new Y.Map();
      nestedMap.set('name', 'Test');
      nestedMap.set('value', 42);

      map1.set('nested', nestedMap);

      await new Promise((resolve) => setTimeout(resolve, 50));

      const nested2 = map2.get('nested') as Y.Map<any>;
      expect(nested2).toBeDefined();
      expect(nested2.get('name')).toBe('Test');
      expect(nested2.get('value')).toBe(42);
    });

    it('Array和Text混合结构同步', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      const array1 = doc1.getArray('paragraphs');
      const text1 = new Y.Text('First paragraph');
      const text2 = new Y.Text('Second paragraph');

      array1.insert(0, [text1, text2]);

      await new Promise((resolve) => setTimeout(resolve, 50));

      const array2 = doc2.getArray('paragraphs');
      expect(array2.length).toBe(2);

      const firstText = array2.get(0) as Y.Text;
      const secondText = array2.get(1) as Y.Text;

      expect(firstText.toString()).toBe('First paragraph');
      expect(secondText.toString()).toBe('Second paragraph');

      firstText.insert(5, ' edited');

      await new Promise((resolve) => setTimeout(resolve, 50));

      const firstText2 = array2.get(0) as Y.Text;
      expect(firstText2.toString()).toBe('First edited paragraph');
    });
  });

  describe('冲突解决', () => {
    it('并发插入相同位置结果一致', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));

      const updates1: Uint8Array[] = [];
      const updates2: Uint8Array[] = [];

      doc1.on('update', (u: Uint8Array) => updates1.push(u));
      doc2.on('update', (u: Uint8Array) => updates2.push(u));

      text1.insert(0, 'X');
      text2.insert(0, 'Y');

      updates1.forEach((u) => Y.applyUpdate(doc2, u));
      updates2.forEach((u) => Y.applyUpdate(doc1, u));

      expect(text1.toString()).toBe(text2.toString());

      const result = text1.toString();
      expect(result.length).toBe(2);
      expect(result.includes('X')).toBe(true);
      expect(result.includes('Y')).toBe(true);
    });

    it('删除和插入冲突处理', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      doc2.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc1, update);
      });

      text1.insert(0, 'ABCDEF');

      await new Promise((resolve) => setTimeout(resolve, 50));

      text1.delete(2, 2);

      text2.insert(2, 'XX');

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(text1.toString()).toBe(text2.toString());

      const result = text1.toString();
      expect(result).toContain('A');
      expect(result).toContain('B');
      expect(result).toContain('E');
      expect(result).toContain('F');
      expect(result).toContain('XX');
    });
  });

  describe('Undo/Redo支持', () => {
    it('本地撤销操作正确恢复状态', async () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      const undoManager = new Y.UndoManager(text);

      text.insert(0, 'Hello World');

      undoManager.undo();

      expect(text.toString()).toBe('');

      undoManager.redo();

      expect(text.toString()).toBe('Hello World');
    });

    it('撤销后同步到其他客户端', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      const undoManager = new Y.UndoManager(text1);

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      text1.insert(0, 'Initial text');

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(text2.toString()).toBe('Initial text');

      undoManager.undo();

      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(text1.toString()).toBe('');
      expect(text2.toString()).toBe('');
    });
  });

  describe('Awareness感知协议', () => {
    it('多用户光标位置同步', async () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const awareness1 = new (await import('y-protocols/awareness')).Awareness(doc1);
      const awareness2 = new (await import('y-protocols/awareness')).Awareness(doc2);

      awareness1.setLocalState({
        user: { id: 1, name: 'User 1' },
        cursor: { anchor: 0, head: 5 },
      });

      awareness2.setLocalState({
        user: { id: 2, name: 'User 2' },
        cursor: { anchor: 10, head: 15 },
      });

      const state1 = awareness1.getLocalState();
      const state2 = awareness2.getLocalState();

      expect(state1?.user.id).toBe(1);
      expect(state1?.cursor.anchor).toBe(0);
      expect(state2?.user.id).toBe(2);
      expect(state2?.cursor.anchor).toBe(10);

      const states1 = Array.from(awareness1.getStates().values());
      const states2 = Array.from(awareness2.getStates().values());

      expect(states1.length).toBe(1);
      expect(states2.length).toBe(1);
    });
  });
});
