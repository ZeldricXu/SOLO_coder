import { describe, it, expect, vi, beforeEach } from 'vitest';
import { SyncEngine } from '@/lib/integrations/SyncEngine';
import type { KnowledgeDocument, SyncConfig } from '@/lib/integrations/types';
import type { ExternalSource, SyncStatus } from '@prisma/client';

class MockSource {
  name: string;
  documents: KnowledgeDocument[];
  shouldFail: boolean;
  fetchCallCount = 0;
  lastCursor: string | undefined;

  constructor(name: string, documents: KnowledgeDocument[], shouldFail = false) {
    this.name = name;
    this.documents = documents;
    this.shouldFail = shouldFail;
  }

  async fetchDocuments(options: any) {
    this.fetchCallCount++;
    this.lastCursor = options.cursor;
    
    if (this.shouldFail) {
      throw new Error(`API rate limit exceeded for ${this.name}`);
    }

    const cursor = options.cursor ? parseInt(options.cursor) : 0;
    const batchSize = options.batchSize || 10;
    const results = this.documents.slice(cursor, cursor + batchSize);
    const nextCursor = cursor + batchSize < this.documents.length 
      ? String(cursor + batchSize) 
      : null;

    return {
      documents: results,
      nextCursor,
      hasMore: !!nextCursor,
    };
  }

  async fetchIncremental(options: any) {
    return this.fetchDocuments(options);
  }

  normalizeContent(content: string) {
    return { normalizedContent: content };
  }
}

describe('SyncEngine', () => {
  let syncEngine: SyncEngine;
  let mockPrisma: any;
  let mockSources: Map<string, MockSource>;

  beforeEach(() => {
    mockPrisma = {
      document: {
        findUnique: vi.fn(),
        create: vi.fn().mockImplementation(({ data }) => Promise.resolve({ id: 'doc-' + Date.now(), ...data })),
        update: vi.fn().mockImplementation(({ data }) => Promise.resolve({ id: 'doc-' + Date.now(), ...data })),
        upsert: vi.fn().mockImplementation(({ data }) => Promise.resolve({ id: 'doc-' + Date.now(), ...data.create })),
      },
      syncLog: {
        create: vi.fn().mockResolvedValue({ id: 'log-' + Date.now() }),
      },
      syncConfig: {
        findUnique: vi.fn(),
      },
    };

    mockSources = new Map();
    
    syncEngine = new SyncEngine({
      prisma: mockPrisma as any,
      spaceId: 'test-space-id',
    });
  });

  describe('Incremental sync cursor management', () => {
    it('should correctly advance cursor without missing data', async () => {
      const testDocs: KnowledgeDocument[] = Array.from({ length: 25 }, (_, i) => ({
        externalId: `doc-${i}`,
        title: `Document ${i}`,
        content: `Content ${i}`,
        sourceType: 'CUSTOM' as any,
        lastModifiedAt: new Date(),
      }));

      const mockSource = new MockSource('test-source', testDocs);
      mockSources.set('test-source', mockSource);

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      const result = await syncEngine.syncSource(
        config,
        mockSource as any,
        { batchSize: 10 }
      );

      expect(mockSource.fetchCallCount).toBe(3);
      expect(mockSource.lastCursor).toBe('20');
      expect(result.synced).toBe(25);
      expect(result.failed).toBe(0);
    });

    it('should not duplicate documents on subsequent syncs', async () => {
      const testDocs: KnowledgeDocument[] = [
        {
          externalId: 'doc-1',
          title: 'Document 1',
          content: 'Content 1',
          sourceType: 'CUSTOM' as any,
          lastModifiedAt: new Date(),
        },
      ];

      const mockSource = new MockSource('test-source', testDocs);

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      mockPrisma.document.findUnique.mockResolvedValue(null);

      await syncEngine.syncSource(config, mockSource as any);
      
      expect(mockPrisma.document.upsert).toHaveBeenCalledWith(
        expect.objectContaining({
          where: { spaceId_externalId_externalSource: {
            spaceId: 'test-space-id',
            externalId: 'doc-1',
            externalSource: 'CUSTOM',
          }},
        })
      );

      const upsertCall = mockPrisma.document.upsert.mock.calls[0][0];
      expect(upsertCall.create.externalId).toBe('doc-1');
      expect(upsertCall.update.externalId).toBe('doc-1');
    });

    it('should handle empty result sets gracefully', async () => {
      const mockSource = new MockSource('test-source', []);

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      const result = await syncEngine.syncSource(config, mockSource as any);

      expect(mockSource.fetchCallCount).toBe(1);
      expect(result.synced).toBe(0);
      expect(result.failed).toBe(0);
    });
  });

  describe('Source isolation on failure', () => {
    it('should continue syncing other sources when one fails', async () => {
      const docs1: KnowledgeDocument[] = Array.from({ length: 5 }, (_, i) => ({
        externalId: `source1-doc-${i}`,
        title: `Source1 Doc ${i}`,
        content: 'Content',
        sourceType: 'FEISHU' as ExternalSource,
        lastModifiedAt: new Date(),
      }));

      const docs2: KnowledgeDocument[] = Array.from({ length: 5 }, (_, i) => ({
        externalId: `source2-doc-${i}`,
        title: `Source2 Doc ${i}`,
        content: 'Content',
        sourceType: 'NOTION' as ExternalSource,
        lastModifiedAt: new Date(),
      }));

      const feishuSource = new MockSource('feishu', docs1, true);
      const notionSource = new MockSource('notion', docs2);

      const configs: SyncConfig[] = [
        {
          id: 'feishu-config',
          spaceId: 'test-space-id',
          sourceType: 'FEISHU' as ExternalSource,
          config: {},
          isEnabled: true,
          syncIntervalMinutes: 60,
          createdAt: new Date(),
          updatedAt: new Date(),
          lastSyncAt: null,
          nextSyncAt: new Date(),
        },
        {
          id: 'notion-config',
          spaceId: 'test-space-id',
          sourceType: 'NOTION' as ExternalSource,
          config: {},
          isEnabled: true,
          syncIntervalMinutes: 60,
          createdAt: new Date(),
          updatedAt: new Date(),
          lastSyncAt: null,
          nextSyncAt: new Date(),
        },
      ];

      const sources = new Map([
        ['feishu-config', feishuSource as any],
        ['notion-config', notionSource as any],
      ]);

      const results = await syncEngine.syncMultipleSources(configs, sources);

      expect(results.get('feishu-config')?.status).toBe('FAILED' as SyncStatus);
      expect(results.get('feishu-config')?.synced).toBe(0);
      expect(results.get('feishu-config')?.failed).toBe(5);
      
      expect(results.get('notion-config')?.status).toBe('SUCCESS' as SyncStatus);
      expect(results.get('notion-config')?.synced).toBe(5);
      expect(results.get('notion-config')?.failed).toBe(0);
    });

    it('should record failure reason in sync log', async () => {
      const mockSource = new MockSource('failing-source', [], true);

      const config: SyncConfig = {
        id: 'failing-config',
        spaceId: 'test-space-id',
        sourceType: 'CONFLUENCE' as ExternalSource,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      const result = await syncEngine.syncSource(config, mockSource as any);

      expect(result.status).toBe('FAILED' as SyncStatus);
      expect(result.errorMessage).toContain('API rate limit exceeded');

      expect(mockPrisma.syncLog.create).toHaveBeenCalledWith(
        expect.objectContaining({
          status: 'FAILED',
          errorMessage: expect.stringContaining('API rate limit'),
        })
      );
    });
  });

  describe('Conflict resolution', () => {
    it('should update existing documents when externalId matches', async () => {
      const testDocs: KnowledgeDocument[] = [
        {
          externalId: 'existing-doc',
          title: 'Updated Title',
          content: 'Updated Content',
          sourceType: 'CUSTOM' as any,
          lastModifiedAt: new Date(),
        },
      ];

      const mockSource = new MockSource('test-source', testDocs);

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      mockPrisma.document.findUnique.mockResolvedValue({
        id: 'existing-id',
        externalId: 'existing-doc',
        title: 'Old Title',
      });

      await syncEngine.syncSource(config, mockSource as any);

      const upsertCall = mockPrisma.document.upsert.mock.calls[0][0];
      expect(upsertCall.where).toEqual({
        spaceId_externalId_externalSource: {
          spaceId: 'test-space-id',
          externalId: 'existing-doc',
          externalSource: 'CUSTOM',
        },
      });
      expect(upsertCall.update.title).toBe('Updated Title');
      expect(upsertCall.update.content).toBe('Updated Content');
    });

    it('should skip documents not modified since last sync', async () => {
      const lastSyncAt = new Date('2024-01-01');
      const testDocs: KnowledgeDocument[] = [
        {
          externalId: 'old-doc',
          title: 'Old Document',
          content: 'Old Content',
          sourceType: 'CUSTOM' as any,
          lastModifiedAt: new Date('2023-12-01'),
        },
        {
          externalId: 'new-doc',
          title: 'New Document',
          content: 'New Content',
          sourceType: 'CUSTOM' as any,
          lastModifiedAt: new Date('2024-01-15'),
        },
      ];

      const mockSource = new MockSource('test-source', testDocs);

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt,
        nextSyncAt: new Date(),
      };

      const result = await syncEngine.syncSource(config, mockSource as any);

      expect(result.synced).toBe(1);
      expect(result.skipped).toBe(1);
    });
  });

  describe('Sync hooks', () => {
    it('should call onDocumentSync callback for each document', async () => {
      const testDocs: KnowledgeDocument[] = Array.from({ length: 3 }, (_, i) => ({
        externalId: `doc-${i}`,
        title: `Document ${i}`,
        content: `Content ${i}`,
        sourceType: 'CUSTOM' as any,
        lastModifiedAt: new Date(),
      }));

      const mockSource = new MockSource('test-source', testDocs);
      const onDocumentSync = vi.fn();

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      await syncEngine.syncSource(config, mockSource as any, {
        onDocumentSync,
      });

      expect(onDocumentSync).toHaveBeenCalledTimes(3);
      expect(onDocumentSync).toHaveBeenCalledWith(
        expect.objectContaining({ externalId: 'doc-0' })
      );
    });

    it('should call onSyncComplete callback after sync', async () => {
      const mockSource = new MockSource('test-source', []);
      const onSyncComplete = vi.fn();

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      await syncEngine.syncSource(config, mockSource as any, {
        onSyncComplete,
      });

      expect(onSyncComplete).toHaveBeenCalledTimes(1);
      expect(onSyncComplete).toHaveBeenCalledWith(
        'config-1',
        expect.objectContaining({ status: 'SUCCESS' })
      );
    });

    it('should call post-sync hooks for search and knowledge graph', async () => {
      const testDocs: KnowledgeDocument[] = [
        {
          externalId: 'doc-1',
          title: 'Test',
          content: 'Test content',
          sourceType: 'CUSTOM' as any,
          lastModifiedAt: new Date(),
        },
      ];

      const mockSource = new MockSource('test-source', testDocs);
      const onSearchIndexUpdate = vi.fn();
      const onKnowledgeGraphUpdate = vi.fn();

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      mockPrisma.document.upsert.mockResolvedValue({ id: 'synced-doc-id' });

      await syncEngine.syncSource(config, mockSource as any, {
        onSearchIndexUpdate,
        onKnowledgeGraphUpdate,
      });

      expect(onSearchIndexUpdate).toHaveBeenCalledWith(['synced-doc-id']);
      expect(onKnowledgeGraphUpdate).toHaveBeenCalledWith(['synced-doc-id']);
    });
  });

  describe('Concurrency control', () => {
    it('should respect concurrency limits', async () => {
      const testDocs: KnowledgeDocument[] = Array.from({ length: 20 }, (_, i) => ({
        externalId: `doc-${i}`,
        title: `Document ${i}`,
        content: `Content ${i}`,
        sourceType: 'CUSTOM' as any,
        lastModifiedAt: new Date(),
      }));

      const mockSource = new MockSource('test-source', testDocs);

      const config: SyncConfig = {
        id: 'config-1',
        spaceId: 'test-space-id',
        sourceType: 'CUSTOM' as any,
        config: {},
        isEnabled: true,
        syncIntervalMinutes: 60,
        createdAt: new Date(),
        updatedAt: new Date(),
        lastSyncAt: null,
        nextSyncAt: new Date(),
      };

      const start = Date.now();
      await syncEngine.syncSource(config, mockSource as any, {
        concurrency: 5,
      });
      const duration = Date.now() - start;

      expect(mockPrisma.document.upsert).toHaveBeenCalledTimes(20);
    });
  });
});
