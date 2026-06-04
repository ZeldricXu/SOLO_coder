import { describe, it, expect, beforeEach, vi } from 'vitest';
import * as Y from 'yjs';
import WebSocket from 'ws';
import { createServer } from 'http';

class MockYjsWebSocketServer {
  private docs: Map<string, Y.Doc> = new Map();
  private connections: Map<string, Set<WebSocket>> = new Map();

  handleConnection(ws: WebSocket, docId: string) {
    if (!this.docs.has(docId)) {
      this.docs.set(docId, new Y.Doc());
    }
    if (!this.connections.has(docId)) {
      this.connections.set(docId, new Set());
    }

    const doc = this.docs.get(docId)!;
    this.connections.get(docId)!.add(ws);

    const updateListener = (update: Uint8Array, origin: any) => {
      if (origin !== ws) {
        ws.send(this.encodeSyncMessage(update));
      }
    };

    doc.on('update', updateListener);

    ws.on('message', (data: Buffer) => {
      const message = new Uint8Array(data);
      this.handleMessage(doc, ws, message);
    });

    ws.on('close', () => {
      this.connections.get(docId)?.delete(ws);
      doc.off('update', updateListener);
    });

    const stateVector = Y.encodeStateVector(doc);
    ws.send(this.encodeStateVector(stateVector));
  }

  private handleMessage(doc: Y.Doc, origin: WebSocket, message: Uint8Array) {
    const decoder = require('lib0/decoding').createDecoder(message);
    const messageType = require('lib0/decoding').readVarUint(decoder);

    if (messageType === 0) {
      const update = require('lib0/decoding').readVarUint8Array(decoder);
      Y.applyUpdate(doc, update, origin);
    } else if (messageType === 1) {
      const sv = require('lib0/decoding').readVarUint8Array(decoder);
      const update = Y.encodeStateAsUpdate(doc, sv);
      origin.send(this.encodeSyncMessage(update));
    }
  }

  private encodeSyncMessage(update: Uint8Array): Uint8Array {
    const encoder = require('lib0/encoding').createEncoder();
    require('lib0/encoding').writeVarUint(encoder, 0);
    require('lib0/encoding').writeVarUint8Array(encoder, update);
    return require('lib0/encoding').toUint8Array(encoder);
  }

  private encodeStateVector(sv: Uint8Array): Uint8Array {
    const encoder = require('lib0/encoding').createEncoder();
    require('lib0/encoding').writeVarUint(encoder, 1);
    require('lib0/encoding').writeVarUint8Array(encoder, sv);
    return require('lib0/encoding').toUint8Array(encoder);
  }

  getDoc(docId: string): Y.Doc | undefined {
    return this.docs.get(docId);
  }
}

describe('Multi-Client Collaborative Editing', () => {
  let server: any;
  let yjsServer: MockYjsWebSocketServer;
  const PORT = 12345;

  beforeEach(async () => {
    yjsServer = new MockYjsWebSocketServer();
    server = createServer();

    server.on('upgrade', (request: any, socket: any, head: any) => {
      const url = new URL(request.url, 'http://localhost');
      const docId = url.searchParams.get('docId') || 'default';
      
      const wsServer = new WebSocket.Server({ noServer: true });
      wsServer.handleUpgrade(request, socket, head, (ws) => {
        yjsServer.handleConnection(ws, docId);
      });
    });

    await new Promise<void>((resolve) => server.listen(PORT, resolve));
  });

  afterEach(async () => {
    await new Promise<void>((resolve) => server.close(resolve));
  });

  const connectClient = (docId: string): Promise<{ ws: WebSocket; doc: Y.Doc }> => {
    return new Promise((resolve) => {
      const ws = new WebSocket(`ws://localhost:${PORT}?docId=${docId}`);
      const doc = new Y.Doc();

      ws.on('open', () => {
        doc.on('update', (update: Uint8Array) => {
          const encoder = require('lib0/encoding').createEncoder();
          require('lib0/encoding').writeVarUint(encoder, 0);
          require('lib0/encoding').writeVarUint8Array(encoder, update);
          ws.send(require('lib0/encoding').toUint8Array(encoder));
        });

        ws.on('message', (data: Buffer) => {
          const message = new Uint8Array(data);
          const decoder = require('lib0/decoding').createDecoder(message);
          const type = require('lib0/decoding').readVarUint(decoder);
          const payload = require('lib0/decoding').readVarUint8Array(decoder);

          if (type === 0) {
            Y.applyUpdate(doc, payload, ws);
          } else if (type === 1) {
            const update = Y.encodeStateAsUpdate(doc, payload);
            const encoder = require('lib0/encoding').createEncoder();
            require('lib0/encoding').writeVarUint(encoder, 0);
            require('lib0/encoding').writeVarUint8Array(encoder, update);
            ws.send(require('lib0/encoding').toUint8Array(encoder));
          }
        });

        setTimeout(() => resolve({ ws, doc }), 50);
      });
    });
  };

  describe('Basic synchronization', () => {
    it('should sync text between two connected clients', async () => {
      const client1 = await connectClient('test-doc-1');
      const client2 = await connectClient('test-doc-1');

      const text1 = client1.doc.getText('content');
      const text2 = client2.doc.getText('content');

      text1.insert(0, 'Hello from client 1');

      await new Promise((r) => setTimeout(r, 100));

      expect(text2.toString()).toBe('Hello from client 1');

      client1.ws.close();
      client2.ws.close();
    });

    it('should sync edits from multiple clients', async () => {
      const client1 = await connectClient('test-doc-2');
      const client2 = await connectClient('test-doc-2');
      const client3 = await connectClient('test-doc-2');

      const text1 = client1.doc.getText('content');
      const text2 = client2.doc.getText('content');
      const text3 = client3.doc.getText('content');

      text1.insert(0, 'A');
      await new Promise((r) => setTimeout(r, 50));

      text2.insert(text2.length, 'B');
      await new Promise((r) => setTimeout(r, 50));

      text3.insert(text3.length, 'C');
      await new Promise((r) => setTimeout(r, 100));

      const result = text1.toString();
      expect(result).toContain('A');
      expect(result).toContain('B');
      expect(result).toContain('C');
      expect(text2.toString()).toBe(result);
      expect(text3.toString()).toBe(result);

      client1.ws.close();
      client2.ws.close();
      client3.ws.close();
    });

    it('should have consistent state on server', async () => {
      const client1 = await connectClient('test-doc-3');
      const client2 = await connectClient('test-doc-3');

      const text1 = client1.doc.getText('content');
      const text2 = client2.doc.getText('content');

      text1.insert(0, 'Server state test');
      await new Promise((r) => setTimeout(r, 100));

      const serverDoc = yjsServer.getDoc('test-doc-3');
      expect(serverDoc).toBeDefined();
      expect(serverDoc!.getText('content').toString()).toBe('Server state test');

      client1.ws.close();
      client2.ws.close();
    });
  });

  describe('Disconnect and reconnect', () => {
    it('should preserve offline edits and sync on reconnect', async () => {
      const client1 = await connectClient('test-doc-4');
      const text1 = client1.doc.getText('content');

      text1.insert(0, 'Initial');
      await new Promise((r) => setTimeout(r, 50));

      client1.ws.close();
      await new Promise((r) => setTimeout(r, 50));

      text1.insert(text1.length, ' + offline edit');

      const client1Reconnected = await connectClient('test-doc-4');
      Y.applyUpdate(client1Reconnected.doc, Y.encodeStateAsUpdate(client1.doc));

      await new Promise((r) => setTimeout(r, 100));

      const serverDoc = yjsServer.getDoc('test-doc-4');
      expect(serverDoc!.getText('content').toString()).toContain('Initial');
      expect(serverDoc!.getText('content').toString()).toContain('offline edit');

      client1Reconnected.ws.close();
    });

    it('should handle multiple disconnect-reconnect cycles', async () => {
      const docId = 'test-doc-5';
      let localDoc = new Y.Doc();

      for (let i = 0; i < 3; i++) {
        const client = await connectClient(docId);
        Y.applyUpdate(client.doc, Y.encodeStateAsUpdate(localDoc));

        const text = client.doc.getText('content');
        text.insert(text.length, `Edit${i} `);

        await new Promise((r) => setTimeout(r, 50));
        localDoc = client.doc;
        client.ws.close();
        await new Promise((r) => setTimeout(r, 50));
      }

      const finalClient = await connectClient(docId);
      await new Promise((r) => setTimeout(r, 50));

      const finalText = finalClient.doc.getText('content').toString();
      expect(finalText).toContain('Edit0');
      expect(finalText).toContain('Edit1');
      expect(finalText).toContain('Edit2');

      finalClient.ws.close();
    });
  });

  describe('Conflict resolution', () => {
    it('should resolve concurrent inserts deterministically', async () => {
      const client1 = await connectClient('test-doc-6');
      const client2 = await connectClient('test-doc-6');

      const text1 = client1.doc.getText('content');
      const text2 = client2.doc.getText('content');

      text1.insert(0, 'XXXX');
      text2.insert(0, 'YYYY');

      await new Promise((r) => setTimeout(r, 100));

      const result1 = text1.toString();
      const result2 = text2.toString();

      expect(result1).toBe(result2);
      expect(result1.length).toBe(8);

      client1.ws.close();
      client2.ws.close();
    });

    it('should resolve concurrent deletes', async () => {
      const client1 = await connectClient('test-doc-7');
      const client2 = await connectClient('test-doc-7');

      const text1 = client1.doc.getText('content');
      text1.insert(0, 'ABCDEFG');
      await new Promise((r) => setTimeout(r, 50));

      const text2 = client2.doc.getText('content');

      text1.delete(0, 3);
      text2.delete(4, 3);

      await new Promise((r) => setTimeout(r, 100));

      expect(text1.toString()).toBe(text2.toString());
      expect(text1.toString().length).toBeLessThan(7);

      client1.ws.close();
      client2.ws.close();
    });
  });

  describe('Large scale operations', () => {
    it('should handle many concurrent clients', async () => {
      const docId = 'test-doc-8';
      const clients = [];
      const numClients = 10;

      for (let i = 0; i < numClients; i++) {
        clients.push(await connectClient(docId));
      }

      await new Promise((r) => setTimeout(r, 100));

      for (let i = 0; i < numClients; i++) {
        const text = clients[i].doc.getText('content');
        text.insert(text.length, `C${i} `);
      }

      await new Promise((r) => setTimeout(r, 200));

      const results = clients.map((c) => c.doc.getText('content').toString());
      const firstResult = results[0];

      for (let i = 1; i < numClients; i++) {
        expect(results[i]).toBe(firstResult);
      }

      for (let i = 0; i < numClients; i++) {
        clients[i].ws.close();
      }
    });

    it('should handle large text payloads', async () => {
      const client1 = await connectClient('test-doc-9');
      const client2 = await connectClient('test-doc-9');

      const largeText = 'Line '.repeat(1000);
      const text1 = client1.doc.getText('content');

      text1.insert(0, largeText);

      await new Promise((r) => setTimeout(r, 200));

      expect(client2.doc.getText('content').toString()).toBe(largeText);

      client1.ws.close();
      client2.ws.close();
    });
  });

  describe('Document isolation', () => {
    it('should keep different documents isolated', async () => {
      const clientA1 = await connectClient('doc-A');
      const clientA2 = await connectClient('doc-A');
      const clientB1 = await connectClient('doc-B');

      clientA1.doc.getText('content').insert(0, 'Content for A');
      clientB1.doc.getText('content').insert(0, 'Content for B');

      await new Promise((r) => setTimeout(r, 100));

      expect(clientA2.doc.getText('content').toString()).toBe('Content for A');
      expect(clientB1.doc.getText('content').toString()).toBe('Content for B');

      const serverDocA = yjsServer.getDoc('doc-A');
      const serverDocB = yjsServer.getDoc('doc-B');

      expect(serverDocA!.getText('content').toString()).toBe('Content for A');
      expect(serverDocB!.getText('content').toString()).toBe('Content for B');

      clientA1.ws.close();
      clientA2.ws.close();
      clientB1.ws.close();
    });
  });
});
