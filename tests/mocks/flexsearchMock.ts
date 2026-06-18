class MockIndex {
  private docs: Map<string, any> = new Map();
  private config: any;

  constructor(config: any) {
    this.config = config;
  }

  add(doc: any) {
    this.docs.set(doc.id, { ...doc });
  }

  update(doc: any) {
    this.docs.set(doc.id, { ...doc });
  }

  remove(id: string) {
    this.docs.delete(id);
  }

  search(query: string, options: any = {}): any[] {
    const q = query.toLowerCase();
    const fields = options.field || ['title', 'content', 'tags'];
    const limit = options.limit;
    const results: any[] = [];

    for (const field of fields) {
      let fieldResults: any[] = [];
      
      for (const [id, doc] of this.docs) {
        const fieldValue = (doc[field] || '').toString().toLowerCase();
        if (fieldValue.includes(q)) {
          fieldResults.push(id);
        }
      }

      if (limit !== undefined && limit !== null) {
        if (limit <= 0) {
          fieldResults = [];
        } else {
          fieldResults = fieldResults.slice(0, limit);
        }
      }

      if (fieldResults.length > 0) {
        results.push({
          field,
          result: fieldResults,
        });
      }
    }

    return results;
  }
}

class MockDocument {
  private index: MockIndex;

  constructor(config: any) {
    this.index = new MockIndex(config);
  }

  add(doc: any) {
    this.index.add(doc);
  }

  update(doc: any) {
    this.index.update(doc);
  }

  remove(id: string) {
    this.index.remove(id);
  }

  search(query: string, options?: any) {
    return this.index.search(query, options);
  }
}

const FlexSearchMock = {
  Document: MockDocument,
  Index: MockIndex,
  default: MockDocument,
};

export default FlexSearchMock;
export { MockDocument, MockIndex };
