import * as Y from 'yjs';
import { encodeStateVector, encodeStateAsUpdate, applyUpdate, Doc } from 'yjs';
import * as crypto from 'crypto';

function computeStateVectorHash(doc: Doc): string {
  const sv = encodeStateVector(doc);
  return crypto.createHash('sha256').update(sv).digest('hex');
}

describe('Yjs Collaboration Resilience', () => {
  describe('State hash verification', () => {
    it('should compute consistent hashes for identical document states', () => {
      const doc1 = new Doc();
      const map1 = doc1.getMap('scene');
      map1.set('object1', { x: 10, y: 20 });

      const doc2 = new Doc();
      const update = encodeStateAsUpdate(doc1);
      applyUpdate(doc2, update);

      const hash1 = computeStateVectorHash(doc1);
      const hash2 = computeStateVectorHash(doc2);

      expect(hash1).toBe(hash2);
    });

    it('should produce different hashes for different document states', () => {
      const doc1 = new Doc();
      doc1.getMap('scene').set('x', 1);

      const doc2 = new Doc();
      doc2.getMap('scene').set('x', 2);

      const hash1 = computeStateVectorHash(doc1);
      const hash2 = computeStateVectorHash(doc2);

      expect(hash1).not.toBe(hash2);
    });
  });

  describe('Full sync recovery', () => {
    it('should recover from diverged states via full sync', () => {
      const serverDoc = new Doc();
      const clientDoc = new Doc();

      const initUpdate = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, initUpdate);

      const serverMap = serverDoc.getMap('scene');
      const clientMap = clientDoc.getMap('scene');

      serverMap.set('obj1', { id: 'obj1', x: 0, y: 0 });
      const update1 = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, update1);

      clientMap.set('obj2', { id: 'obj2', x: 100, y: 100 });
      const lostUpdate = encodeStateAsUpdate(clientDoc);

      const divergedClient = new Doc();
      applyUpdate(divergedClient, initUpdate);
      applyUpdate(divergedClient, update1);

      expect(computeStateVectorHash(divergedClient)).not.toBe(computeStateVectorHash(clientDoc));

      const serverHash = computeStateVectorHash(serverDoc);
      const fullUpdate = encodeStateAsUpdate(serverDoc);
      applyUpdate(divergedClient, fullUpdate);

      const divergedMap = divergedClient.getMap('scene');
      expect(divergedMap.get('obj1')).toEqual(serverMap.get('obj1'));

      expect(computeStateVectorHash(divergedClient)).toBe(serverHash);
    });

    it('should detect divergence via hash comparison', () => {
      const doc1 = new Doc();
      const doc2 = new Doc();

      const init = encodeStateAsUpdate(doc1);
      applyUpdate(doc2, init);

      doc1.getMap('scene').set('a', 1);

      const hash1 = computeStateVectorHash(doc1);
      const hash2 = computeStateVectorHash(doc2);

      expect(hash1).not.toBe(hash2);

      const fullUpdate = encodeStateAsUpdate(doc1);
      applyUpdate(doc2, fullUpdate);

      const newHash1 = computeStateVectorHash(doc1);
      const newHash2 = computeStateVectorHash(doc2);

      expect(newHash1).toBe(newHash2);
    });
  });

  describe('Weak network simulation', () => {
    it('should correctly handle lost updates', () => {
      const serverDoc = new Doc();
      const clientA = new Doc();
      const clientB = new Doc();

      const init = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientA, init);
      applyUpdate(clientB, init);

      const updates: Uint8Array[] = [];

      clientA.on('update', (update: Uint8Array) => {
        updates.push(update);
      });

      const mapA = clientA.getMap('scene');
      mapA.set('obj', { id: '1', x: 0, y: 0 });

      expect(updates.length).toBe(1);

      applyUpdate(serverDoc, updates[0]);

      const lostIdx = updates.length;
      mapA.set('obj', { id: '1', x: 10, y: 0 });
      expect(updates.length).toBe(2);

      const beforeHash = computeStateVectorHash(clientA);

      applyUpdate(serverDoc, updates[lostIdx]);

      const mapS = serverDoc.getMap('scene');
      mapS.set('obj', { id: '1', x: 5, y: 5 });

      const serverToClient = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientB, serverToClient);

      const fullSync = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientA, fullSync);

      const afterHashA = computeStateVectorHash(clientA);
      const afterHashB = computeStateVectorHash(clientB);
      const afterHashS = computeStateVectorHash(serverDoc);

      expect(afterHashA).not.toBe(beforeHash);
      expect(afterHashA).toBe(afterHashS);
      expect(afterHashB).toBe(afterHashS);
    });

    it('should handle concurrent edits during disconnection', () => {
      const serverDoc = new Doc();
      const client1 = new Doc();
      const client2 = new Doc();

      const init = encodeStateAsUpdate(serverDoc);
      applyUpdate(client1, init);
      applyUpdate(client2, init);

      const map1 = client1.getMap('objects');
      const map2 = client2.getMap('objects');

      map1.set('objA', { id: 'objA', position: { x: 0, y: 0 } });
      const update1 = encodeStateAsUpdate(client1);
      applyUpdate(client2, update1);
      applyUpdate(serverDoc, update1);

      map1.set('objA', { id: 'objA', position: { x: 100, y: 0 } });
      const update2 = encodeStateAsUpdate(client1);

      map2.set('objB', { id: 'objB', position: { x: 0, y: 0 } });
      const update3 = encodeStateAsUpdate(client2);

      applyUpdate(client1, update3);
      applyUpdate(client2, update2);
      applyUpdate(serverDoc, update2);
      applyUpdate(serverDoc, update3);

      const finalUpdate = encodeStateAsUpdate(serverDoc);
      applyUpdate(client1, finalUpdate);
      applyUpdate(client2, finalUpdate);

      const hash1 = computeStateVectorHash(client1);
      const hash2 = computeStateVectorHash(client2);
      const hashS = computeStateVectorHash(serverDoc);

      expect(hash1).toBe(hashS);
      expect(hash2).toBe(hashS);

      const client1Objects = client1.getMap('objects');
      const client2Objects = client2.getMap('objects');

      expect(client1Objects.get('objA')).toEqual(client2Objects.get('objA'));
      expect(client1Objects.get('objB')).toEqual(client2Objects.get('objB'));
    });

    it('should detect divergence detection logic', () => {
      const serverDoc = new Doc();
      const clientDoc = new Doc();

      const init = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, init);

      const serverMap = serverDoc.getMap('scene');
      const clientMap = clientDoc.getMap('scene');

      serverMap.set('obj1', { id: 'obj1', x: 0, y: 0 });
      const update1 = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, update1);

      const baseHash = computeStateVectorHash(clientDoc);

      clientMap.set('obj2', { id: 'obj2', x: 10, y: 10 });

      const hashClientAfterClient = computeStateVectorHash(clientDoc);
      const hashServer = computeStateVectorHash(serverDoc);

      expect(hashClientAfterClient).not.toBe(hashServer);

      serverMap.set('obj3', { id: 'obj3', x: 5, y: 5 });
      const hashServerAfterUpdate = computeStateVectorHash(serverDoc);

      const serverFullUpdate = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, serverFullUpdate);

      expect(clientMap.get('obj1')).toEqual(serverMap.get('obj1'));
      expect(clientMap.get('obj3')).toEqual(serverMap.get('obj3'));
      expect(clientMap.get('obj2')).toEqual({ id: 'obj2', x: 10, y: 10 });

      const hashAfterMerge = computeStateVectorHash(clientDoc);
      expect(hashAfterMerge).not.toBe(hashServerAfterUpdate);

      const freshClientDoc = new Doc();
      applyUpdate(freshClientDoc, serverFullUpdate);
      expect(computeStateVectorHash(freshClientDoc)).toBe(hashServerAfterUpdate);
    });
  });

  describe('Data integrity after recovery', () => {
    it('should preserve all data after full sync recovery', () => {
      const serverDoc = new Doc();
      const objects = serverDoc.getArray('objects');

      for (let i = 0; i < 50; i++) {
        objects.push([{ id: `obj${i}`, x: i * 10, y: i * 5 }]);
      }

      const clientDoc = new Doc();
      const init = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, init);

      for (let i = 0; i < 20; i++) {
        clientDoc.getArray('objects').push([{ id: `clientObj${i}`, x: i, y: i }]);
      }

      const divergedHash = computeStateVectorHash(clientDoc);
      const serverHashBefore = computeStateVectorHash(serverDoc);
      expect(divergedHash).not.toBe(serverHashBefore);

      for (let i = 0; i < 30; i++) {
        serverDoc.getArray('objects').push([{ id: `serverObj${i}`, x: i * 100, y: i * 50 }]);
      }

      const serverHashAfter = computeStateVectorHash(serverDoc);
      const fullUpdate = encodeStateAsUpdate(serverDoc);
      applyUpdate(clientDoc, fullUpdate);

      const clientObjects = clientDoc.getArray('objects');
      const serverObjects = serverDoc.getArray('objects');

      const clientData: any[] = [];
      for (let i = 0; i < clientObjects.length; i++) {
        clientData.push(clientObjects.get(i));
      }
      const serverData: any[] = [];
      for (let i = 0; i < serverObjects.length; i++) {
        serverData.push(serverObjects.get(i));
      }

      for (const obj of serverData) {
        const found = clientData.some((c: any) => c.id === (obj as any).id);
        expect(found).toBe(true);
      }

      for (let i = 0; i < 20; i++) {
        const found = clientData.some((c: any) => c.id === `clientObj${i}`);
        expect(found).toBe(true);
      }

      const hashBeforeReload = computeStateVectorHash(clientDoc);
      expect(hashBeforeReload).not.toBe(serverHashAfter);

      const reloadedDoc = new Doc();
      applyUpdate(reloadedDoc, fullUpdate);
      expect(computeStateVectorHash(reloadedDoc)).toBe(serverHashAfter);
      
      const reloadedObjects = reloadedDoc.getArray('objects');
      for (let i = 0; i < serverObjects.length; i++) {
        expect(reloadedObjects.get(i)).toEqual(serverObjects.get(i));
      }
    });
  });
});
