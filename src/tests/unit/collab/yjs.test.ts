import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';

describe('Yjs CRDT Synchronization', () => {
  describe('Y.Text operations', () => {
    it('should insert text correctly', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      text.insert(0, 'Hello World');

      expect(text.toString()).toBe('Hello World');
    });

    it('should delete text correctly', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      text.insert(0, 'Hello World');
      text.delete(5, 6);

      expect(text.toString()).toBe('Hello');
    });

    it('should handle multiple insertions', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      text.insert(0, 'Hello');
      text.insert(5, ' World');
      text.insert(11, '!');

      expect(text.toString()).toBe('Hello World!');
    });

    it('should track insertion positions', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      text.insert(0, 'ABCD');
      text.insert(2, 'X');

      expect(text.toString()).toBe('ABXCD');
    });
  });

  describe('Multi-client synchronization', () => {
    it('should sync text between two clients', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      doc1.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc2, update);
      });

      doc2.on('update', (update: Uint8Array) => {
        Y.applyUpdate(doc1, update);
      });

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      text1.insert(0, 'Hello from client 1');

      expect(text2.toString()).toBe('Hello from client 1');
    });

    it('should handle concurrent inserts without conflicts', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const updates1: Uint8Array[] = [];
      const updates2: Uint8Array[] = [];

      doc1.on('update', (u) => updates1.push(u));
      doc2.on('update', (u) => updates2.push(u));

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      text1.insert(0, 'ABC');
      text2.insert(0, 'XYZ');

      updates1.forEach((u) => Y.applyUpdate(doc2, u));
      updates2.forEach((u) => Y.applyUpdate(doc1, u));

      const result1 = text1.toString();
      const result2 = text2.toString();

      expect(result1).toBe(result2);
      expect(result1).toContain('ABC');
      expect(result1).toContain('XYZ');
    });

    it('should maintain causal ordering', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      text1.insert(0, 'First ');
      Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));

      text2.insert(6, 'Second');
      Y.applyUpdate(doc1, Y.encodeStateAsUpdate(doc2));

      expect(text1.toString()).toBe('First Second');
      expect(text2.toString()).toBe('First Second');
    });

    it('should converge to same state after multiple operations', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      for (let i = 0; i < 10; i++) {
        text1.insert(text1.length, `A${i} `);
        text2.insert(text2.length, `B${i} `);
      }

      Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));
      Y.applyUpdate(doc1, Y.encodeStateAsUpdate(doc2));

      expect(text1.toString()).toBe(text2.toString());
    });
  });

  describe('State vector recovery', () => {
    it('should compute state vector correctly', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      text.insert(0, 'Hello World');

      const stateVector = Y.encodeStateVector(doc);
      expect(stateVector).toBeInstanceOf(Uint8Array);
      expect(stateVector.length).toBeGreaterThan(0);
    });

    it('should recover missing updates using state vector', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      text1.insert(0, 'First change');
      text2.insert(0, 'Second change');

      const sv1 = Y.encodeStateVector(doc1);
      const sv2 = Y.encodeStateVector(doc2);

      const diff1to2 = Y.encodeStateAsUpdate(doc1, sv2);
      const diff2to1 = Y.encodeStateAsUpdate(doc2, sv1);

      Y.applyUpdate(doc1, diff2to1);
      Y.applyUpdate(doc2, diff1to2);

      expect(text1.toString()).toBe(text2.toString());
    });

    it('should handle offline edits and sync on reconnection', () => {
      const serverDoc = new Y.Doc();
      const clientDoc = new Y.Doc();

      const serverText = serverDoc.getText('content');
      const clientText = clientDoc.getText('content');

      serverText.insert(0, 'Initial content');
      Y.applyUpdate(clientDoc, Y.encodeStateAsUpdate(serverDoc));

      const svClient = Y.encodeStateVector(clientDoc);

      serverText.insert(serverText.length, ' + server edit');
      clientText.insert(clientText.length, ' + client edit');

      const serverToClient = Y.encodeStateAsUpdate(serverDoc, svClient);
      const clientToServer = Y.encodeStateAsUpdate(clientDoc, Y.encodeStateVector(serverDoc));

      Y.applyUpdate(clientDoc, serverToClient);
      Y.applyUpdate(serverDoc, clientToServer);

      expect(serverText.toString()).toBe(clientText.toString());
      expect(serverText.toString()).toContain('Initial content');
      expect(serverText.toString()).toContain('server edit');
      expect(serverText.toString()).toContain('client edit');
    });
  });

  describe('CRDT consistency guarantees', () => {
    it('should be commutative - order of updates does not matter', () => {
      const docA = new Y.Doc();
      const docB = new Y.Doc();

      const update1 = new Y.Doc();
      update1.getText('content').insert(0, 'First');
      const update1Data = Y.encodeStateAsUpdate(update1);

      const update2 = new Y.Doc();
      update2.getText('content').insert(0, 'Second');
      const update2Data = Y.encodeStateAsUpdate(update2);

      Y.applyUpdate(docA, update1Data);
      Y.applyUpdate(docA, update2Data);

      Y.applyUpdate(docB, update2Data);
      Y.applyUpdate(docB, update1Data);

      expect(docA.getText('content').toString()).toBe(docB.getText('content').toString());
    });

    it('should be idempotent - applying same update twice has no effect', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      const updateDoc = new Y.Doc();
      updateDoc.getText('content').insert(0, 'Test');
      const update = Y.encodeStateAsUpdate(updateDoc);

      Y.applyUpdate(doc, update);
      const stateAfter1 = text.toString();

      Y.applyUpdate(doc, update);
      const stateAfter2 = text.toString();

      expect(stateAfter1).toBe(stateAfter2);
    });

    it('should be associative - grouping does not matter', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const u1 = new Y.Doc();
      u1.getText('content').insert(0, 'A');

      const u2 = new Y.Doc();
      u2.getText('content').insert(0, 'B');

      const u3 = new Y.Doc();
      u3.getText('content').insert(0, 'C');

      const temp = new Y.Doc();
      Y.applyUpdate(temp, Y.encodeStateAsUpdate(u1));
      Y.applyUpdate(temp, Y.encodeStateAsUpdate(u2));
      const combined12 = Y.encodeStateAsUpdate(temp);

      Y.applyUpdate(doc1, combined12);
      Y.applyUpdate(doc1, Y.encodeStateAsUpdate(u3));

      Y.applyUpdate(doc2, Y.encodeStateAsUpdate(u1));
      const temp2 = new Y.Doc();
      Y.applyUpdate(temp2, Y.encodeStateAsUpdate(u2));
      Y.applyUpdate(temp2, Y.encodeStateAsUpdate(u3));
      Y.applyUpdate(doc2, Y.encodeStateAsUpdate(temp2));

      expect(doc1.getText('content').toString()).toBe(doc2.getText('content').toString());
    });
  });

  describe('Complex text operations', () => {
    it('should handle interleaved inserts and deletes', () => {
      const doc1 = new Y.Doc();
      const doc2 = new Y.Doc();

      const text1 = doc1.getText('content');
      const text2 = doc2.getText('content');

      const ops1: Uint8Array[] = [];
      const ops2: Uint8Array[] = [];

      doc1.on('update', (u) => ops1.push(u));
      doc2.on('update', (u) => ops2.push(u));

      text1.insert(0, 'Hello World');
      text2.insert(11, '!');
      text1.delete(0, 6);
      text2.insert(0, 'Goodbye ');

      ops1.forEach((u) => Y.applyUpdate(doc2, u));
      ops2.forEach((u) => Y.applyUpdate(doc1, u));

      expect(text1.toString()).toBe(text2.toString());
    });

    it('should handle formatting attributes', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      text.insert(0, 'Hello World');
      text.format(0, 5, { bold: true });
      text.format(6, 5, { italic: true });

      const delta = text.toDelta();
      
      expect(delta[0].attributes?.bold).toBe(true);
      expect(delta[2].attributes?.italic).toBe(true);
    });
  });

  describe('Data persistence', () => {
    it('should encode and decode document state', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');
      
      text.insert(0, 'Hello World');
      text.format(0, 5, { bold: true });

      const encoded = Y.encodeStateAsUpdate(doc);
      const restoredDoc = new Y.Doc();
      Y.applyUpdate(restoredDoc, encoded);

      expect(restoredDoc.getText('content').toString()).toBe('Hello World');
    });

    it('should handle large documents', () => {
      const doc = new Y.Doc();
      const text = doc.getText('content');

      for (let i = 0; i < 1000; i++) {
        text.insert(text.length, `Line ${i}\n`);
      }

      const encoded = Y.encodeStateAsUpdate(doc);
      const restoredDoc = new Y.Doc();
      Y.applyUpdate(restoredDoc, encoded);

      expect(restoredDoc.getText('content').toString()).toBe(text.toString());
    });
  });

  describe('Awareness protocol', () => {
    it('should track user presence', () => {
      const doc = new Y.Doc();
      const awareness = new (require('y-protocols/awareness').Awareness)(doc);

      awareness.setLocalState({
        user: { id: 'user-1', name: 'Test User', color: '#ff0000' },
        cursor: { position: 10, selection: null },
      });

      const localState = awareness.getLocalState();
      expect(localState.user.id).toBe('user-1');
      expect(localState.cursor.position).toBe(10);
    });

    it('should update cursor position', () => {
      const doc = new Y.Doc();
      const awareness = new (require('y-protocols/awareness').Awareness)(doc);

      awareness.setLocalState({
        user: { id: 'user-1' },
        cursor: { position: 0 },
      });

      awareness.setLocalStateField('cursor', { position: 42 });

      expect(awareness.getLocalState().cursor.position).toBe(42);
    });
  });
});
