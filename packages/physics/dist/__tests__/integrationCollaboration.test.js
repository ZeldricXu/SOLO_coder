import * as Y from 'yjs';
describe('Integration: Collaboration with Yjs CRDT', () => {
    it('should sync scene state between two clients via Yjs', () => {
        const doc1 = new Y.Doc();
        const doc2 = new Y.Doc();
        doc1.on('update', (update) => {
            Y.applyUpdate(doc2, update);
        });
        doc2.on('update', (update) => {
            Y.applyUpdate(doc1, update);
        });
        const sceneMap1 = doc1.getMap('scene');
        sceneMap1.set('name', 'Test Scene');
        const objectsArray1 = doc1.getArray('objects');
        objectsArray1.push([
            new Y.Map(Object.entries({
                id: 'obj-1',
                type: 'sphere',
                positionX: '0',
                positionY: '5',
                positionZ: '0',
            }))
        ]);
        const sceneMap2 = doc2.getMap('scene');
        expect(sceneMap2.get('name')).toBe('Test Scene');
        const objectsArray2 = doc2.getArray('objects');
        expect(objectsArray2.length).toBe(1);
        const obj2 = objectsArray2.get(0);
        expect(obj2.get('id')).toBe('obj-1');
        expect(obj2.get('type')).toBe('sphere');
    });
    it('should resolve concurrent edits to different properties', () => {
        const doc1 = new Y.Doc();
        const doc2 = new Y.Doc();
        const objectsArray1 = doc1.getArray('objects');
        objectsArray1.push([
            new Y.Map(Object.entries({
                id: 'obj-1',
                positionX: '0',
                positionY: '0',
                positionZ: '0',
            }))
        ]);
        Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));
        const obj1 = doc1.getArray('objects').get(0);
        const obj2 = doc2.getArray('objects').get(0);
        obj1.set('positionX', '5');
        obj2.set('positionY', '10');
        Y.applyUpdate(doc1, Y.encodeStateAsUpdate(doc2));
        Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));
        const final1 = doc1.getArray('objects').get(0);
        const final2 = doc2.getArray('objects').get(0);
        expect(final1.get('positionX')).toBe('5');
        expect(final1.get('positionY')).toBe('10');
        expect(final2.get('positionX')).toBe('5');
        expect(final2.get('positionY')).toBe('10');
    });
    it('should resolve concurrent conflicting edits to the same property (last-write-wins)', () => {
        const doc1 = new Y.Doc();
        const doc2 = new Y.Doc();
        const sceneMap1 = doc1.getMap('scene');
        sceneMap1.set('name', 'Original');
        Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));
        const sceneMap2 = doc2.getMap('scene');
        sceneMap1.set('name', 'Client1 Edit');
        sceneMap2.set('name', 'Client2 Edit');
        Y.applyUpdate(doc1, Y.encodeStateAsUpdate(doc2));
        Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));
        const name1 = doc1.getMap('scene').get('name');
        const name2 = doc2.getMap('scene').get('name');
        expect(name1).toBe(name2);
        expect(['Client1 Edit', 'Client2 Edit']).toContain(name1);
    });
    it('should handle concurrent object additions', () => {
        const doc1 = new Y.Doc();
        const doc2 = new Y.Doc();
        const objects1 = doc1.getArray('objects');
        objects1.push([
            new Y.Map(Object.entries({ id: 'obj-1', type: 'sphere' }))
        ]);
        Y.applyUpdate(doc2, Y.encodeStateAsUpdate(doc1));
        doc1.on('update', (update) => {
            Y.applyUpdate(doc2, update);
        });
        doc2.on('update', (update) => {
            Y.applyUpdate(doc1, update);
        });
        const objects2 = doc2.getArray('objects');
        objects1.push([
            new Y.Map(Object.entries({ id: 'obj-2', type: 'box' }))
        ]);
        objects2.push([
            new Y.Map(Object.entries({ id: 'obj-3', type: 'cylinder' }))
        ]);
        const final1 = doc1.getArray('objects');
        const final2 = doc2.getArray('objects');
        expect(final1.length).toBe(3);
        expect(final2.length).toBe(3);
        const ids1 = [0, 1, 2].map(i => final1.get(i).get('id'));
        const ids2 = [0, 1, 2].map(i => final2.get(i).get('id'));
        expect(ids1.sort()).toEqual(ids2.sort());
        expect(ids1.sort()).toEqual(['obj-1', 'obj-2', 'obj-3']);
    });
    it('should track cursor positions of collaborators', () => {
        const doc1 = new Y.Doc();
        const doc2 = new Y.Doc();
        doc1.on('update', (update) => {
            Y.applyUpdate(doc2, update);
        });
        const cursors1 = doc1.getMap('cursors');
        cursors1.set('user1', JSON.stringify({ x: 1, y: 2, z: 3 }));
        const cursors2 = doc2.getMap('cursors');
        expect(cursors2.get('user1')).toBeDefined();
        const cursor = JSON.parse(cursors2.get('user1'));
        expect(cursor.x).toBe(1);
        expect(cursor.y).toBe(2);
        expect(cursor.z).toBe(3);
    });
});
//# sourceMappingURL=integrationCollaboration.test.js.map