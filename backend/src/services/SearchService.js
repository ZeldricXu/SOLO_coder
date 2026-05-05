const { Client } = require('@elastic/elasticsearch');
const Document = require('../models/Document');
const _ = require('lodash');

class SearchService {
  constructor() {
    this.client = null;
    this.indexName = 'wikihub_documents';
    this.isConnected = false;
    this.pendingUpdates = new Map();
    this.updateTimers = new Map();
    this.lastIndexedStates = new Map();
    
    this.BULK_FLUSH_INTERVAL = 1000;
    this.MAX_PENDING_UPDATES = 100;
    this.MIN_CHANGE_THRESHOLD = 0.01;
  }

  async init() {
    try {
      const node = process.env.ELASTICSEARCH_NODE || 'http://localhost:9200';
      const username = process.env.ELASTICSEARCH_USERNAME;
      const password = process.env.ELASTICSEARCH_PASSWORD;
      
      const config = { node };
      if (username && password) {
        config.auth = { username, password };
      }
      
      this.client = new Client(config);
      
      await this.client.ping();
      console.log('Elasticsearch connected successfully');
      
      await this.ensureIndex();
      this.isConnected = true;
      
    } catch (error) {
      console.warn('Elasticsearch connection failed, falling back to MongoDB search:', error.message);
      this.isConnected = false;
    }
  }

  async ensureIndex() {
    try {
      const indexExists = await this.client.indices.exists({
        index: this.indexName
      });
      
      if (!indexExists) {
        await this.client.indices.create({
          index: this.indexName,
          mappings: {
            properties: {
              doc_id: { type: 'keyword' },
              title: { 
                type: 'text',
                analyzer: 'ik_max_word',
                search_analyzer: 'ik_smart'
              },
              content: { 
                type: 'text',
                analyzer: 'ik_max_word',
                search_analyzer: 'ik_smart'
              },
              folder_id: { type: 'keyword' },
              created_by: { type: 'keyword' },
              collaborators: { type: 'keyword' },
              last_edited_at: { type: 'date' },
              created_at: { type: 'date' },
              _content_hash: { type: 'keyword', index: false },
              _title_hash: { type: 'keyword', index: false }
            }
          },
          settings: {
            analysis: {
              analyzer: {
                default: {
                  type: 'ik_max_word'
                }
              }
            }
          }
        });
        
        console.log(`Created index: ${this.indexName}`);
      }
    } catch (error) {
      console.warn('Failed to create index, using defaults:', error.message);
    }
  }

  generateHash(str) {
    if (!str) return '';
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
      const char = str.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }
    return hash.toString(36);
  }

  calculateChangeRatio(oldStr, newStr) {
    if (!oldStr && !newStr) return 0;
    if (!oldStr || !newStr) return 1;
    if (oldStr === newStr) return 0;

    const len1 = oldStr.length;
    const len2 = newStr.length;
    const maxLen = Math.max(len1, len2);

    let commonPrefix = 0;
    while (commonPrefix < maxLen && oldStr[commonPrefix] === newStr[commonPrefix]) {
      commonPrefix++;
    }

    let commonSuffix = 0;
    while (commonSuffix < maxLen - commonPrefix && 
           oldStr[len1 - 1 - commonSuffix] === newStr[len2 - 1 - commonSuffix]) {
      commonSuffix++;
    }

    const changedChars = maxLen - commonPrefix - commonSuffix;
    return changedChars / maxLen;
  }

  computePartialUpdates(oldDoc, newDoc) {
    const updates = {};
    const partialUpdates = [];

    if (oldDoc.title !== newDoc.title) {
      updates.title = newDoc.title;
      partialUpdates.push({
        field: 'title',
        oldValue: oldDoc.title,
        newValue: newDoc.title,
        type: 'replace'
      });
    }

    if (oldDoc.folder_id !== newDoc.folder_id) {
      updates.folder_id = newDoc.folder_id;
      partialUpdates.push({
        field: 'folder_id',
        type: 'replace'
      });
    }

    if (oldDoc.last_edited_by !== newDoc.last_edited_by) {
      updates.last_edited_by = newDoc.last_edited_by;
      partialUpdates.push({
        field: 'last_edited_by',
        type: 'replace'
      });
    }

    if (oldDoc.last_edited_at !== newDoc.last_edited_at) {
      updates.last_edited_at = newDoc.last_edited_at;
      partialUpdates.push({
        field: 'last_edited_at',
        type: 'replace'
      });
    }

    if (oldDoc.collaborators && newDoc.collaborators) {
      const oldCollabs = JSON.stringify(oldDoc.collaborators.sort());
      const newCollabs = JSON.stringify(newDoc.collaborators.sort());
      if (oldCollabs !== newCollabs) {
        updates.collaborators = newDoc.collaborators;
        partialUpdates.push({
          field: 'collaborators',
          type: 'replace'
        });
      }
    }

    const contentChangeRatio = this.calculateChangeRatio(oldDoc.content, newDoc.content);
    
    if (contentChangeRatio > 0) {
      if (contentChangeRatio >= this.MIN_CHANGE_THRESHOLD) {
        updates.content = newDoc.content;
        partialUpdates.push({
          field: 'content',
          changeRatio: contentChangeRatio,
          type: contentChangeRatio > 0.3 ? 'full_update' : 'partial_update'
        });
      } else {
        partialUpdates.push({
          field: 'content',
          changeRatio: contentChangeRatio,
          type: 'skipped',
          reason: 'change_below_threshold'
        });
      }
    }

    return {
      updates,
      partialUpdates,
      hasSignificantChange: Object.keys(updates).length > 0
    };
  }

  async indexDocument(doc) {
    if (!this.isConnected) {
      console.log('Elasticsearch not connected, skipping index');
      return;
    }

    const docId = doc.doc_id;
    const lastState = this.lastIndexedStates.get(docId);

    if (lastState) {
      const { updates, partialUpdates, hasSignificantChange } = this.computePartialUpdates(lastState, doc);
      
      if (!hasSignificantChange) {
        console.log(`Skipping index for ${docId}: no significant changes`);
        return {
          skipped: true,
          reason: 'no_significant_changes',
          partialUpdates
        };
      }

      if (Object.keys(updates).length === 1 && !updates.content) {
        await this.updateDocument(docId, updates);
        this.lastIndexedStates.set(docId, { ...doc });
        
        console.log(`Partial updated index for ${docId}:`, Object.keys(updates));
        return {
          updated: true,
          type: 'partial',
          fields: Object.keys(updates),
          partialUpdates
        };
      }
    }

    try {
      await this.client.index({
        index: this.indexName,
        id: docId,
        document: {
          doc_id: doc.doc_id,
          title: doc.title,
          content: doc.content,
          folder_id: doc.folder_id,
          created_by: doc.created_by,
          collaborators: doc.collaborators || [],
          last_edited_at: doc.last_edited_at,
          created_at: doc.created_at,
          _content_hash: this.generateHash(doc.content),
          _title_hash: this.generateHash(doc.title)
        }
      });
      
      this.lastIndexedStates.set(docId, { ...doc });
      
      console.log(`Indexed document: ${docId}`);
      return {
        indexed: true,
        type: 'full'
      };
    } catch (error) {
      console.error('Failed to index document:', error.message);
      throw error;
    }
  }

  queueIncrementalUpdate(doc) {
    if (!this.isConnected) return;

    const docId = doc.doc_id;
    
    this.pendingUpdates.set(docId, {
      doc: { ...doc },
      timestamp: new Date()
    });

    if (!this.updateTimers.has(docId)) {
      const timer = setTimeout(async () => {
        try {
          const pending = this.pendingUpdates.get(docId);
          if (pending) {
            await this.indexDocument(pending.doc);
            this.pendingUpdates.delete(docId);
          }
        } catch (error) {
          console.error('Queued update failed:', error);
        } finally {
          this.updateTimers.delete(docId);
        }
      }, this.BULK_FLUSH_INTERVAL);

      this.updateTimers.set(docId, timer);
    }

    if (this.pendingUpdates.size >= this.MAX_PENDING_UPDATES) {
      this.flushPendingUpdates();
    }
  }

  async flushPendingUpdates() {
    if (!this.isConnected || this.pendingUpdates.size === 0) return;

    const docsToIndex = Array.from(this.pendingUpdates.values()).map(p => p.doc);
    
    this.pendingUpdates.clear();
    
    for (const [docId, timer] of this.updateTimers) {
      clearTimeout(timer);
    }
    this.updateTimers.clear();

    console.log(`Flushing ${docsToIndex.length} pending updates...`);
    await this.bulkIndex(docsToIndex);
  }

  async updateDocument(docId, updates) {
    if (!this.isConnected) {
      return;
    }

    try {
      await this.client.update({
        index: this.indexName,
        id: docId,
        doc: updates
      });
      
      console.log(`Incrementally updated document index: ${docId}`, Object.keys(updates));
    } catch (error) {
      if (error.meta && error.meta.statusCode === 404) {
        console.log(`Document ${docId} not found in index, performing full index`);
        const doc = await Document.findOne({ doc_id });
        if (doc) {
          await this.indexDocument(doc);
        }
      } else {
        console.error('Failed to update document index:', error.message);
        throw error;
      }
    }
  }

  async updateDocumentContent(docId, newContent, oldContent = null) {
    if (!this.isConnected) return;

    const changeRatio = oldContent ? 
      this.calculateChangeRatio(oldContent, newContent) : 1;

    if (changeRatio < this.MIN_CHANGE_THRESHOLD) {
      console.log(`Skipping content update for ${docId}: change ratio ${(changeRatio * 100).toFixed(2)}% below threshold`);
      return { skipped: true, changeRatio };
    }

    if (changeRatio < 0.3) {
      await this.updateDocument(docId, {
        content: newContent,
        _content_hash: this.generateHash(newContent)
      });
      
      const lastState = this.lastIndexedStates.get(docId);
      if (lastState) {
        lastState.content = newContent;
        this.lastIndexedStates.set(docId, lastState);
      }

      return {
        updated: true,
        type: 'incremental',
        changeRatio
      };
    } else {
      const doc = await Document.findOne({ doc_id });
      if (doc) {
        await this.indexDocument(doc);
      }

      return {
        updated: true,
        type: 'full',
        changeRatio
      };
    }
  }

  async deleteDocument(docId) {
    if (!this.isConnected) {
      return;
    }

    try {
      await this.client.delete({
        index: this.indexName,
        id: docId
      });
      
      this.lastIndexedStates.delete(docId);
      this.pendingUpdates.delete(docId);
      
      if (this.updateTimers.has(docId)) {
        clearTimeout(this.updateTimers.get(docId));
        this.updateTimers.delete(docId);
      }
      
      console.log(`Deleted document index: ${docId}`);
    } catch (error) {
      console.error('Failed to delete document index:', error.message);
    }
  }

  async search(query, options = {}) {
    const { folder_id, limit = 20, offset = 0, user_id } = options;
    
    if (this.isConnected) {
      try {
        return await this.elasticsearchSearch(query, { folder_id, limit, offset, user_id });
      } catch (error) {
        console.warn('Elasticsearch search failed, falling back to MongoDB:', error.message);
      }
    }
    
    return await this.mongodbSearch(query, { folder_id, limit, offset, user_id });
  }

  async elasticsearchSearch(query, options) {
    const { folder_id, limit, offset, user_id } = options;
    
    const searchQuery = {
      query: {
        bool: {
          must: [
            {
              multi_match: {
                query,
                fields: ['title^3', 'content'],
                fuzziness: 'AUTO'
              }
            }
          ],
          filter: []
        }
      },
      highlight: {
        fields: {
          title: { pre_tags: ['<mark>'], post_tags: ['</mark>'] },
          content: { pre_tags: ['<mark>'], post_tags: ['</mark>'], fragment_size: 150, number_of_fragments: 3 }
        }
      },
      from: offset,
      size: limit,
      sort: [
        { _score: 'desc' },
        { last_edited_at: 'desc' }
      ]
    };
    
    if (folder_id) {
      searchQuery.query.bool.filter.push({
        term: { folder_id }
      });
    }
    
    if (user_id) {
      searchQuery.query.bool.filter.push({
        bool: {
          should: [
            { term: { created_by: user_id } },
            { term: { collaborators: user_id } }
          ]
        }
      });
    }
    
    const result = await this.client.search({
      index: this.indexName,
      ...searchQuery
    });
    
    const total = result.hits.total.value || result.hits.total;
    const results = result.hits.hits.map(hit => {
      const source = hit._source;
      const highlights = hit.highlight || {};
      
      let snippet = '';
      if (highlights.content) {
        snippet = highlights.content.join('... ');
      } else if (highlights.title) {
        snippet = highlights.title[0];
      } else {
        snippet = source.content?.substring(0, 200) + '...' || '';
      }
      
      return {
        doc_id: source.doc_id,
        title: source.title,
        snippet,
        match_score: hit._score,
        folder_id: source.folder_id,
        last_edited_at: source.last_edited_at
      };
    });
    
    return {
      results,
      total,
      offset,
      limit,
      query,
      search_engine: 'elasticsearch'
    };
  }

  async mongodbSearch(query, options) {
    const { folder_id, limit, offset, user_id } = options;
    
    const searchQuery = {
      $text: { $search: query }
    };
    
    if (folder_id) {
      searchQuery.folder_id = folder_id;
    }
    
    if (user_id) {
      searchQuery.$or = [
        { created_by: user_id },
        { collaborators: user_id }
      ];
    }
    
    const total = await Document.countDocuments(searchQuery);
    
    const documents = await Document.find(searchQuery)
      .sort({ score: { $meta: 'textScore' }, last_edited_at: -1 })
      .skip(offset)
      .limit(limit)
      .exec();
    
    const results = documents.map(doc => {
      const content = doc.content || '';
      const queryLower = query.toLowerCase();
      const contentLower = content.toLowerCase();
      
      let snippet = '';
      const index = contentLower.indexOf(queryLower);
      if (index >= 0) {
        const start = Math.max(0, index - 75);
        const end = Math.min(content.length, index + query.length + 75);
        snippet = (start > 0 ? '...' : '') + 
                  content.substring(start, end) + 
                  (end < content.length ? '...' : '');
      } else {
        snippet = content.substring(0, 200) + '...';
      }
      
      return {
        doc_id: doc.doc_id,
        title: doc.title,
        snippet,
        match_score: 1.0,
        folder_id: doc.folder_id,
        last_edited_at: doc.last_edited_at
      };
    });
    
    return {
      results,
      total,
      offset,
      limit,
      query,
      search_engine: 'mongodb'
    };
  }

  async bulkIndex(documents) {
    if (!this.isConnected) {
      console.log('Elasticsearch not connected, skipping bulk index');
      return;
    }
    
    try {
      const operations = documents.flatMap(doc => [
        { index: { _index: this.indexName, _id: doc.doc_id } },
        {
          doc_id: doc.doc_id,
          title: doc.title,
          content: doc.content,
          folder_id: doc.folder_id,
          created_by: doc.created_by,
          collaborators: doc.collaborators || [],
          last_edited_at: doc.last_edited_at,
          created_at: doc.created_at,
          _content_hash: this.generateHash(doc.content),
          _title_hash: this.generateHash(doc.title)
        }
      ]);
      
      if (operations.length > 0) {
        await this.client.bulk({ operations, refresh: true });
        
        for (const doc of documents) {
          this.lastIndexedStates.set(doc.doc_id, { ...doc });
        }
        
        console.log(`Bulk indexed ${documents.length} documents`);
      }
    } catch (error) {
      console.error('Bulk index failed:', error.message);
    }
  }

  async rebuildIndex() {
    console.log('Starting index rebuild...');
    
    const allDocs = await Document.find({}).exec();
    console.log(`Found ${allDocs.length} documents to index`);
    
    if (this.isConnected) {
      try {
        await this.client.indices.delete({ index: this.indexName, ignore_unavailable: true });
        await this.ensureIndex();
        await this.bulkIndex(allDocs);
        
        this.lastIndexedStates.clear();
        for (const doc of allDocs) {
          this.lastIndexedStates.set(doc.doc_id, { ...doc });
        }
        
        console.log('Index rebuild complete');
        return { success: true, count: allDocs.length };
      } catch (error) {
        console.error('Index rebuild failed:', error.message);
        return { success: false, error: error.message };
      }
    }
    
    return { success: true, count: allDocs.length, note: 'Using MongoDB text search' };
  }

  getIndexedDocumentCount() {
    return this.lastIndexedStates.size;
  }

  getPendingUpdateCount() {
    return this.pendingUpdates.size;
  }

  async suggest(query) {
    if (!this.isConnected) {
      return { suggestions: [] };
    }
    
    try {
      const result = await this.client.search({
        index: this.indexName,
        suggest: {
          text: query,
          title_suggest: {
            term: {
              field: 'title'
            }
          },
          content_suggest: {
            phrase: {
              field: 'content',
              max_errors: 2
            }
          }
        },
        size: 0
      });
      
      const suggestions = [];
      
      if (result.suggest?.title_suggest) {
        for (const option of result.suggest.title_suggest) {
          if (option.options) {
            for (const opt of option.options) {
              if (!suggestions.find(s => s.text === opt.text)) {
                suggestions.push({ text: opt.text, score: opt.score, type: 'title' });
              }
            }
          }
        }
      }
      
      if (result.suggest?.content_suggest) {
        for (const option of result.suggest.content_suggest) {
          if (option.options) {
            for (const opt of option.options) {
              if (!suggestions.find(s => s.text === opt.text)) {
                suggestions.push({ text: opt.text, score: opt.score, type: 'content' });
              }
            }
          }
        }
      }
      
      return {
        suggestions: suggestions.sort((a, b) => b.score - a.score).slice(0, 10)
      };
    } catch (error) {
      console.error('Suggest failed:', error.message);
      return { suggestions: [] };
    }
  }

  shutdown() {
    this.flushPendingUpdates();
    
    for (const [docId, timer] of this.updateTimers) {
      clearTimeout(timer);
    }
    this.updateTimers.clear();
    
    console.log('Search service shutdown complete');
  }
}

module.exports = new SearchService();
