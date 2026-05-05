const { v4: uuidv4 } = require('uuid');

class OperationalTransform {
  constructor() {
    this.operationQueue = new Map();
    this.versionMap = new Map();
  }

  transformInsert(insertOp, concurrentOp) {
    if (concurrentOp.op_type === 'insert') {
      if (concurrentOp.op_data.position < insertOp.op_data.position) {
        return {
          ...insertOp,
          op_data: {
            ...insertOp.op_data,
            position: insertOp.op_data.position + concurrentOp.op_data.text.length
          }
        };
      } else if (concurrentOp.op_data.position === insertOp.op_data.position) {
        if (insertOp.user_id < concurrentOp.user_id) {
          return insertOp;
        } else {
          return {
            ...insertOp,
            op_data: {
              ...insertOp.op_data,
              position: insertOp.op_data.position + concurrentOp.op_data.text.length
            }
          };
        }
      }
      return insertOp;
    }

    if (concurrentOp.op_type === 'delete') {
      const concurrentStart = concurrentOp.op_data.start || 0;
      const concurrentEnd = concurrentOp.op_data.end || (concurrentStart + (concurrentOp.op_data.length || 0));

      if (insertOp.op_data.position <= concurrentStart) {
        return insertOp;
      } else if (insertOp.op_data.position >= concurrentEnd) {
        return {
          ...insertOp,
          op_data: {
            ...insertOp.op_data,
            position: insertOp.op_data.position - (concurrentEnd - concurrentStart)
          }
        };
      } else {
        return {
          ...insertOp,
          op_data: {
            ...insertOp.op_data,
            position: concurrentStart
          }
        };
      }
    }

    return insertOp;
  }

  transformDelete(deleteOp, concurrentOp) {
    const deleteStart = deleteOp.op_data.start || 0;
    const deleteEnd = deleteOp.op_data.end || (deleteStart + (deleteOp.op_data.length || 0));
    const deleteLength = deleteEnd - deleteStart;

    if (concurrentOp.op_type === 'insert') {
      const insertPos = concurrentOp.op_data.position;
      const insertLength = concurrentOp.op_data.text.length;

      if (insertPos <= deleteStart) {
        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: deleteStart + insertLength,
            end: deleteEnd + insertLength,
            length: deleteLength
          }
        };
      } else if (insertPos >= deleteEnd) {
        return deleteOp;
      } else {
        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: deleteStart,
            end: deleteEnd + insertLength,
            length: deleteLength + insertLength
          }
        };
      }
    }

    if (concurrentOp.op_type === 'delete') {
      const concurrentStart = concurrentOp.op_data.start || 0;
      const concurrentEnd = concurrentOp.op_data.end || (concurrentStart + (concurrentOp.op_data.length || 0));
      const concurrentLength = concurrentEnd - concurrentStart;

      if (concurrentEnd <= deleteStart) {
        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: deleteStart - concurrentLength,
            end: deleteEnd - concurrentLength,
            length: deleteLength
          }
        };
      } else if (concurrentStart >= deleteEnd) {
        return deleteOp;
      } else {
        const newStart = Math.min(deleteStart, concurrentStart);
        const newEnd = Math.max(deleteEnd, concurrentEnd);
        const newLength = newEnd - newStart;

        return {
          ...deleteOp,
          op_data: {
            ...deleteOp.op_data,
            start: newStart,
            end: newEnd,
            length: newLength,
            is_merged: true
          }
        };
      }
    }

    return deleteOp;
  }

  transform(op, concurrentOp) {
    if (!op || !concurrentOp) return op;
    if (op.op_id === concurrentOp.op_id) return op;

    let transformedOp = { ...op, op_data: { ...op.op_data } };

    if (op.op_type === 'insert') {
      transformedOp = this.transformInsert(transformedOp, concurrentOp);
    } else if (op.op_type === 'delete') {
      transformedOp = this.transformDelete(transformedOp, concurrentOp);
    }

    return transformedOp;
  }

  applyOperation(content, op) {
    if (!content) content = '';

    if (op.op_type === 'insert') {
      const position = Math.min(op.op_data.position || 0, content.length);
      const text = op.op_data.text || '';
      return content.slice(0, position) + text + content.slice(position);
    } else if (op.op_type === 'delete') {
      const start = Math.max(0, op.op_data.start || 0);
      const end = Math.min(content.length, op.op_data.end || (start + (op.op_data.length || 0)));
      return content.slice(0, start) + content.slice(end);
    } else if (op.op_type === 'replace') {
      return op.op_data.content !== undefined ? op.op_data.content : content;
    }

    return content;
  }

  detectConflict(op1, op2) {
    if (!op1 || !op2) return false;
    if (op1.op_type === 'replace' || op2.op_type === 'replace') return true;

    if (op1.op_type === 'insert' && op2.op_type === 'insert') {
      const pos1 = op1.op_data.position || 0;
      const pos2 = op2.op_data.position || 0;
      return pos1 === pos2;
    }

    if (op1.op_type === 'delete' && op2.op_type === 'delete') {
      const start1 = op1.op_data.start || 0;
      const end1 = op1.op_data.end || (start1 + (op1.op_data.length || 0));
      const start2 = op2.op_data.start || 0;
      const end2 = op2.op_data.end || (start2 + (op2.op_data.length || 0));
      
      return !(end1 <= start2 || end2 <= start1);
    }

    return false;
  }
}

describe('OperationalTransform (OT) Algorithm', () => {
  let ot;

  beforeEach(() => {
    ot = new OperationalTransform();
  });

  describe('Basic Operations', () => {
    test('should apply insert operation correctly', () => {
      const content = 'Hello World';
      const insertOp = {
        op_type: 'insert',
        op_data: {
          position: 5,
          text: ' Beautiful'
        }
      };

      const result = ot.applyOperation(content, insertOp);
      expect(result).toBe('Hello Beautiful World');
    });

    test('should apply delete operation correctly', () => {
      const content = 'Hello Beautiful World';
      const deleteOp = {
        op_type: 'delete',
        op_data: {
          start: 5,
          end: 15
        }
      };

      const result = ot.applyOperation(content, deleteOp);
      expect(result).toBe('Hello World');
    });

    test('should apply replace operation correctly', () => {
      const content = 'Hello World';
      const replaceOp = {
        op_type: 'replace',
        op_data: {
          content: 'New Content'
        }
      };

      const result = ot.applyOperation(content, replaceOp);
      expect(result).toBe('New Content');
    });
  });

  describe('Conflict Detection', () => {
    test('should detect conflict when inserting at same position', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'insert',
        op_data: { position: 5, text: 'A' }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'insert',
        op_data: { position: 5, text: 'B' }
      };

      const hasConflict = ot.detectConflict(op1, op2);
      expect(hasConflict).toBe(true);
    });

    test('should NOT detect conflict when inserting at different positions', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'insert',
        op_data: { position: 5, text: 'A' }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'insert',
        op_data: { position: 10, text: 'B' }
      };

      const hasConflict = ot.detectConflict(op1, op2);
      expect(hasConflict).toBe(false);
    });

    test('should detect conflict when deleting overlapping ranges', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'delete',
        op_data: { start: 5, end: 15 }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'delete',
        op_data: { start: 10, end: 20 }
      };

      const hasConflict = ot.detectConflict(op1, op2);
      expect(hasConflict).toBe(true);
    });

    test('should NOT detect conflict when deleting non-overlapping ranges', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'delete',
        op_data: { start: 0, end: 5 }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'delete',
        op_data: { start: 10, end: 15 }
      };

      const hasConflict = ot.detectConflict(op1, op2);
      expect(hasConflict).toBe(false);
    });

    test('should detect conflict for replace operations', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'replace',
        op_data: { content: 'Version A' }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'insert',
        op_data: { position: 0, text: 'Prefix ' }
      };

      const hasConflict = ot.detectConflict(op1, op2);
      expect(hasConflict).toBe(true);
    });
  });

  describe('Insert Transformations', () => {
    test('should transform insert when concurrent insert is before it', () => {
      const op1 = {
        op_id: 'op1',
        user_id: 'user2',
        op_type: 'insert',
        op_data: { position: 5, text: 'X' }
      };
      
      const op2 = {
        op_id: 'op2',
        user_id: 'user1',
        op_type: 'insert',
        op_data: { position: 3, text: 'ABC' }
      };

      const transformed = ot.transformInsert(op1, op2);
      expect(transformed.op_data.position).toBe(8);
    });

    test('should transform insert when concurrent insert is after it', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'insert',
        op_data: { position: 3, text: 'ABC' }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'insert',
        op_data: { position: 10, text: 'XYZ' }
      };

      const transformed = ot.transformInsert(op1, op2);
      expect(transformed.op_data.position).toBe(3);
    });

    test('should use user_id ordering for same position insertions', () => {
      const op1 = {
        op_id: 'op1',
        user_id: 'user_a',
        op_type: 'insert',
        op_data: { position: 5, text: 'A' }
      };
      
      const op2 = {
        op_id: 'op2',
        user_id: 'user_b',
        op_type: 'insert',
        op_data: { position: 5, text: 'B' }
      };

      const transformedOp1 = ot.transformInsert(op1, op2);
      const transformedOp2 = ot.transformInsert(op2, op1);

      expect(transformedOp1.op_data.position).toBe(5);
      expect(transformedOp2.op_data.position).toBe(6);
    });

    test('should transform insert when concurrent delete removes content before it', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'insert',
        op_data: { position: 10, text: 'X' }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'delete',
        op_data: { start: 2, end: 5 }
      };

      const transformed = ot.transformInsert(op1, op2);
      expect(transformed.op_data.position).toBe(7);
    });

    test('should transform insert when concurrent delete removes content after it', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'insert',
        op_data: { position: 2, text: 'X' }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'delete',
        op_data: { start: 5, end: 10 }
      };

      const transformed = ot.transformInsert(op1, op2);
      expect(transformed.op_data.position).toBe(2);
    });
  });

  describe('Delete Transformations', () => {
    test('should transform delete when concurrent insert is before it', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'delete',
        op_data: { start: 5, end: 10 }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'insert',
        op_data: { position: 3, text: 'ABC' }
      };

      const transformed = ot.transformDelete(op1, op2);
      expect(transformed.op_data.start).toBe(8);
      expect(transformed.op_data.end).toBe(13);
    });

    test('should transform delete when concurrent insert is after it', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'delete',
        op_data: { start: 2, end: 5 }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'insert',
        op_data: { position: 10, text: 'ABC' }
      };

      const transformed = ot.transformDelete(op1, op2);
      expect(transformed.op_data.start).toBe(2);
      expect(transformed.op_data.end).toBe(5);
    });

    test('should merge overlapping delete operations', () => {
      const op1 = {
        op_id: 'op1',
        op_type: 'delete',
        op_data: { start: 2, end: 7 }
      };
      
      const op2 = {
        op_id: 'op2',
        op_type: 'delete',
        op_data: { start: 5, end: 10 }
      };

      const transformed = ot.transformDelete(op1, op2);
      expect(transformed.op_data.is_merged).toBe(true);
      expect(transformed.op_data.start).toBe(2);
      expect(transformed.op_data.end).toBe(10);
    });
  });

  describe('Multi-step Operation Sequences', () => {
    test('should correctly apply transformed insert operations', () => {
      const initialContent = 'abcdef';
      
      const opA = {
        op_id: 'op_a',
        user_id: 'user_a',
        op_type: 'insert',
        op_data: { position: 3, text: 'X' }
      };
      
      const opB = {
        op_id: 'op_b',
        user_id: 'user_b',
        op_type: 'insert',
        op_data: { position: 3, text: 'Y' }
      };

      const transformedOpA = ot.transform(opA, opB);
      const transformedOpB = ot.transform(opB, opA);

      expect(transformedOpA.op_data.position).toBe(3);
      expect(transformedOpB.op_data.position).toBe(4);

      const resultA = ot.applyOperation(initialContent, transformedOpA);
      const resultB = ot.applyOperation(resultA, transformedOpB);

      expect(resultB).toBe('abcXYdef');
    });

    test('should handle insert-then-delete sequence with transformation', () => {
      const initialContent = 'hello world';
      
      const insertOp = {
        op_id: 'insert',
        op_type: 'insert',
        op_data: { position: 6, text: 'beautiful ' }
      };
      
      const deleteOp = {
        op_id: 'delete',
        op_type: 'delete',
        op_data: { start: 0, end: 5 }
      };

      const transformedDelete = ot.transformDelete(deleteOp, insertOp);
      
      const afterInsert = ot.applyOperation(initialContent, insertOp);
      expect(afterInsert).toBe('hello beautiful world');

      const finalResult = ot.applyOperation(afterInsert, transformedDelete);
      expect(finalResult).toBe(' beautiful world');
    });

    test('should verify commutative property of OT', () => {
      const initialContent = 'abc';
      
      const op1 = {
        op_id: 'op1',
        user_id: 'user_a',
        op_type: 'insert',
        op_data: { position: 1, text: 'X' }
      };
      
      const op2 = {
        op_id: 'op2',
        user_id: 'user_b',
        op_type: 'insert',
        op_data: { position: 2, text: 'Y' }
      };

      const transformedOp1_v2 = ot.transform(op1, op2);
      const transformedOp2_v1 = ot.transform(op2, op1);

      const result1 = ot.applyOperation(ot.applyOperation(initialContent, op1), transformedOp2_v1);
      const result2 = ot.applyOperation(ot.applyOperation(initialContent, op2), transformedOp1_v2);

      expect(result1).toBe('aXYbc');
      expect(result2).toBe('aXYbc');
      expect(result1).toBe(result2);
    });
  });

  describe('Real-world Collaboration Scenarios', () => {
    test('should handle two users typing at the same position', () => {
      const doc = 'Hello ';
      
      const user1Op = {
        op_id: uuidv4(),
        user_id: 'user001',
        op_type: 'insert',
        op_data: { position: 6, text: 'World' }
      };
      
      const user2Op = {
        op_id: uuidv4(),
        user_id: 'user002',
        op_type: 'insert',
        op_data: { position: 6, text: 'Everyone' }
      };

      const hasConflict = ot.detectConflict(user1Op, user2Op);
      expect(hasConflict).toBe(true);

      const transformedUser1 = ot.transform(user1Op, user2Op);
      const transformedUser2 = ot.transform(user2Op, user1Op);

      if (user1Op.user_id < user2Op.user_id) {
        expect(transformedUser1.op_data.position).toBe(6);
        expect(transformedUser2.op_data.position).toBe(6 + 'World'.length);
      } else {
        expect(transformedUser2.op_data.position).toBe(6);
        expect(transformedUser1.op_data.position).toBe(6 + 'Everyone'.length);
      }
    });

    test('should handle concurrent insert and delete on same range', () => {
      const doc = 'The quick brown fox';
      
      const insertOp = {
        op_id: uuidv4(),
        op_type: 'insert',
        op_data: { position: 10, text: ' fast ' }
      };
      
      const deleteOp = {
        op_id: uuidv4(),
        op_type: 'delete',
        op_data: { start: 4, end: 9 }
      };

      const transformedInsert = ot.transformInsert(insertOp, deleteOp);
      const transformedDelete = ot.transformDelete(deleteOp, insertOp);

      const afterInsert = ot.applyOperation(doc, insertOp);
      expect(afterInsert).toBe('The quick  fast brown fox');

      const finalWithDelete = ot.applyOperation(afterInsert, transformedDelete);
      expect(finalWithDelete).toBe('The  fast brown fox');
    });

    test('should handle multiple sequential operations with transformations', () => {
      let doc = 'Start';
      const operations = [];
      
      operations.push({
        op_id: uuidv4(),
        op_type: 'insert',
        op_data: { position: 5, text: ' point' }
      });
      
      operations.push({
        op_id: uuidv4(),
        op_type: 'insert',
        op_data: { position: 11, text: ' added' }
      });
      
      operations.push({
        op_id: uuidv4(),
        op_type: 'delete',
        op_data: { start: 0, end: 5 }
      });

      for (const op of operations) {
        doc = ot.applyOperation(doc, op);
      }

      expect(doc).toBe(' point added');
    });
  });
});
