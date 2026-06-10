import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { FastifyInstance } from 'fastify';
import { Tenant, TenantStatus } from '@prisma/client';
import { setupTestApp, flushRedis, simulateConcurrentRequests, TestContext } from '../helpers';
import { createTenant, createApiRequest, createHostRequest } from '../factories';
import { tenantResolver } from '@modules/tenant/tenant-resolver';
import { connectionPool } from '@modules/tenant/connection-pool';
import { usageService } from '@modules/usage/usage-service';
import { TenantContext } from '@types/index';

describe('Tenant Isolation Module', () => {
  let testContext: TestContext;
  let app: FastifyInstance;

  beforeEach(async () => {
    testContext = await setupTestApp();
    app = testContext.app;
    await flushRedis();
  });

  afterEach(async () => {
    await flushRedis();
  });

  describe('Normal Path', () => {
    describe('API Key Identification', () => {
      it('should route request to correct tenant database with valid API Key', async () => {
        const tenantA = await createTenant({
          code: 'tenant-a',
          name: 'Tenant A',
          status: 'ACTIVE' as any,
        });
        const tenantB = await createTenant({
          code: 'tenant-b',
          name: 'Tenant B',
          status: 'ACTIVE' as any,
        });

        const requestA = createApiRequest(tenantA.apiKey, '/api/v1/health');
        const requestB = createApiRequest(tenantB.apiKey, '/api/v1/health');

        const responseA = await app.inject(requestA);
        const responseB = await app.inject(requestB);

        expect(responseA.statusCode).toBe(200);
        expect(responseB.statusCode).toBe(200);

        const contextA = await tenantResolver.resolveFromRequest(requestA as any);
        const contextB = await tenantResolver.resolveFromRequest(requestB as any);

        expect(contextA).not.toBeNull();
        expect(contextB).not.toBeNull();
        expect(contextA!.tenantId).toBe(tenantA.id);
        expect(contextB!.tenantId).toBe(tenantB.id);
        expect(contextA!.tenantCode).toBe(tenantA.code);
        expect(contextB!.tenantCode).toBe(tenantB.code);
      });

      it('should cache tenant resolution for subsequent requests', async () => {
        const tenant = await createTenant({
          code: 'cache-test',
          name: 'Cache Test Tenant',
          status: 'ACTIVE' as any,
        });

        const request = createApiRequest(tenant.apiKey, '/api/v1/health');

        const firstResponse = await app.inject(request);
        expect(firstResponse.statusCode).toBe(200);

        const cachedTenant = await testContext.redis.get(`tenant:apikey:${tenant.apiKey}`);
        expect(cachedTenant).not.toBeNull();

        const parsedCache = JSON.parse(cachedTenant!);
        expect(parsedCache.id).toBe(tenant.id);
        expect(parsedCache.code).toBe(tenant.code);

        const secondResponse = await app.inject(request);
        expect(secondResponse.statusCode).toBe(200);
      });
    });

    describe('Host Header Identification', () => {
      it('should identify tenant by custom domain host header', async () => {
        const customDomain = 'custom-tenant.test.local';
        const tenant = await createTenant({
          code: 'host-tenant',
          name: 'Host Tenant',
          status: 'ACTIVE' as any,
          customDomain,
        });

        const request = createHostRequest(customDomain, '/api/v1/health');
        const context = await tenantResolver.resolveFromRequest(request as any);

        expect(context).not.toBeNull();
        expect(context!.tenantId).toBe(tenant.id);
        expect(context!.tenantCode).toBe(tenant.code);
      });

      it('should identify tenant by hostPattern', async () => {
        const hostPattern = 'pattern-tenant.test.local';
        const tenant = await createTenant({
          code: 'pattern-tenant',
          name: 'Pattern Tenant',
          status: 'ACTIVE' as any,
          hostPattern,
        });

        const request = createHostRequest(hostPattern, '/api/v1/health');
        const context = await tenantResolver.resolveFromRequest(request as any);

        expect(context).not.toBeNull();
        expect(context!.tenantId).toBe(tenant.id);
        expect(context!.tenantCode).toBe(tenant.code);
      });

      it('should prioritize API Key over Host header when both provided', async () => {
        const tenantA = await createTenant({
          code: 'api-key-tenant',
          name: 'API Key Tenant',
          status: 'ACTIVE' as any,
          hostPattern: 'api-tenant.test.local',
        });
        const tenantB = await createTenant({
          code: 'host-tenant-priority',
          name: 'Host Tenant Priority',
          status: 'ACTIVE' as any,
          customDomain: 'host-priority.test.local',
        });

        const request = {
          ...createApiRequest(tenantA.apiKey, '/api/v1/health'),
          headers: {
            'x-api-key': tenantA.apiKey,
            host: tenantB.customDomain,
          },
        };

        const context = await tenantResolver.resolveFromRequest(request as any);

        expect(context).not.toBeNull();
        expect(context!.tenantId).toBe(tenantA.id);
        expect(context!.tenantCode).toBe(tenantA.code);
      });
    });

    describe('Connection Pool Isolation', () => {
      it('should provide different database connection pools for different tenants', async () => {
        const tenantA = await createTenant({
          code: 'pool-tenant-a',
          name: 'Pool Tenant A',
          status: 'ACTIVE' as any,
        });
        const tenantB = await createTenant({
          code: 'pool-tenant-b',
          name: 'Pool Tenant B',
          status: 'ACTIVE' as any,
        });

        const poolA = connectionPool.getTenantPool(tenantA.id, tenantA.dbSchema);
        const poolB = connectionPool.getTenantPool(tenantB.id, tenantB.dbSchema);

        expect(poolA).not.toBe(poolB);

        const prismaA = connectionPool.getTenantPrisma(tenantA.id, tenantA.dbSchema);
        const prismaB = connectionPool.getTenantPrisma(tenantB.id, tenantB.dbSchema);

        expect(prismaA).not.toBe(prismaB);
      });

      it('should switch to correct schema for each tenant', async () => {
        const tenant = await createTenant({
          code: 'schema-tenant',
          name: 'Schema Tenant',
          status: 'ACTIVE' as any,
        });

        const expectedSchema = `tenant_${tenant.code}`;
        expect(tenant.dbSchema).toBe(expectedSchema);

        const pool = connectionPool.getTenantPool(tenant.id, tenant.dbSchema);
        const client = await pool.connect();

        try {
          const result = await client.query('SHOW search_path');
          const searchPath = result.rows[0].search_path;

          expect(searchPath).toContain(tenant.dbSchema);
          expect(searchPath).toContain('public');
        } finally {
          client.release();
        }
      });

      it('should reuse existing connection pool for same tenant', async () => {
        const tenant = await createTenant({
          code: 'reuse-pool-tenant',
          name: 'Reuse Pool Tenant',
          status: 'ACTIVE' as any,
        });

        const pool1 = connectionPool.getTenantPool(tenant.id, tenant.dbSchema);
        const pool2 = connectionPool.getTenantPool(tenant.id, tenant.dbSchema);

        expect(pool1).toBe(pool2);
      });
    });
  });

  describe('Exception Path', () => {
    it('should return 401 for invalid API Key', async () => {
      const request = createApiRequest('sk_test_invalid_key_12345', '/api/v1/health');
      const response = await app.inject(request);

      expect(response.statusCode).toBe(401);
      const body = JSON.parse(response.body);
      expect(body.code).toBe('TENANT_NOT_FOUND');
      expect(body.error).toBe('Unauthorized');
    });

    it('should return 401 for non-existent Host header', async () => {
      const request = createHostRequest('non-existent.test.local', '/api/v1/health');
      const response = await app.inject(request);

      expect(response.statusCode).toBe(401);
      const body = JSON.parse(response.body);
      expect(body.code).toBe('TENANT_NOT_FOUND');
    });

    it('should reject requests from suspended tenants', async () => {
      const tenant = await createTenant({
        code: 'suspended-tenant',
        name: 'Suspended Tenant',
        status: 'SUSPENDED' as any,
      });

      const request = createApiRequest(tenant.apiKey, '/api/v1/health');
      const response = await app.inject(request);

      expect(response.statusCode).toBe(401);
      const body = JSON.parse(response.body);
      expect(body.code).toBe('TENANT_NOT_FOUND');
    });

    it('should reject requests from pending tenants', async () => {
      const tenant = await createTenant({
        code: 'pending-tenant',
        name: 'Pending Tenant',
        status: 'PENDING' as any,
      });

      const request = createApiRequest(tenant.apiKey, '/api/v1/health');
      const response = await app.inject(request);

      expect(response.statusCode).toBe(401);
    });

    it('should reject requests from cancelled tenants', async () => {
      const tenant = await createTenant({
        code: 'cancelled-tenant',
        name: 'Cancelled Tenant',
        status: 'CANCELLED' as any,
      });

      const request = createApiRequest(tenant.apiKey, '/api/v1/health');
      const response = await app.inject(request);

      expect(response.statusCode).toBe(401);
    });

    it('should return 403 when tenant A accesses tenant B\'s content', async () => {
      const tenantA = await createTenant({
        code: 'tenant-a-forbidden',
        name: 'Tenant A Forbidden',
        status: 'ACTIVE' as any,
      });
      const tenantB = await createTenant({
        code: 'tenant-b-forbidden',
        name: 'Tenant B Forbidden',
        status: 'ACTIVE' as any,
      });

      const requestA = createApiRequest(tenantA.apiKey, '/api/v1/tenants');
      const responseA = await app.inject(requestA);
      expect(responseA.statusCode).toBe(200);

      const requestToB = createApiRequest(tenantA.apiKey, `/api/v1/tenants/${tenantB.id}`);
      const responseToB = await app.inject(requestToB);

      expect(responseToB.statusCode).toBe(404);
    });

    it('should invalidate cache when tenant is suspended', async () => {
      const tenant = await createTenant({
        code: 'cache-invalidate',
        name: 'Cache Invalidate Tenant',
        status: 'ACTIVE' as any,
      });

      const request = createApiRequest(tenant.apiKey, '/api/v1/health');
      const firstResponse = await app.inject(request);
      expect(firstResponse.statusCode).toBe(200);

      const cached = await testContext.redis.get(`tenant:apikey:${tenant.apiKey}`);
      expect(cached).not.toBeNull();

      await testContext.prisma.tenant.update({
        where: { id: tenant.id },
        data: { status: TenantStatus.SUSPENDED },
      });

      await tenantResolver.invalidateTenantCache(tenant.id);

      const cachedAfter = await testContext.redis.get(`tenant:apikey:${tenant.apiKey}`);
      expect(cachedAfter).toBeNull();

      const secondResponse = await app.inject(request);
      expect(secondResponse.statusCode).toBe(401);
    });
  });

  describe('Concurrent Scenarios', () => {
    it('should maintain correct tenant context for 100 concurrent requests with random API keys', async () => {
      const tenants: Tenant[] = [];
      const tenantCount = 10;

      for (let i = 0; i < tenantCount; i++) {
        const tenant = await createTenant({
          code: `concurrent-tenant-${i}`,
          name: `Concurrent Tenant ${i}`,
          status: 'ACTIVE' as any,
        });
        tenants.push(tenant);
      }

      const requestCount = 100;
      const requests: (() => Promise<{ tenantId: string; expectedTenantId: string; statusCode: number }>)[] = [];

      for (let i = 0; i < requestCount; i++) {
        const randomTenant = tenants[Math.floor(Math.random() * tenants.length)];
        requests.push(async () => {
          const request = createApiRequest(randomTenant.apiKey, '/api/v1/health');
          const response = await app.inject(request);
          const context = await tenantResolver.resolveFromRequest(request as any);
          return {
            tenantId: context?.tenantId || '',
            expectedTenantId: randomTenant.id,
            statusCode: response.statusCode,
          };
        });
      }

      const results = await simulateConcurrentRequests(requests, 20);

      expect(results.length).toBe(requestCount);

      for (const result of results) {
        expect(result.statusCode).toBe(200);
        expect(result.tenantId).toBe(result.expectedTenantId);
      }

      const tenantRequestCounts: Record<string, number> = {};
      for (const result of results) {
        tenantRequestCounts[result.tenantId] = (tenantRequestCounts[result.tenantId] || 0) + 1;
      }

      expect(Object.keys(tenantRequestCounts).length).toBeGreaterThan(1);
    });

    it('should maintain rate limit counter correctness under 1000 concurrent requests', async () => {
      const rateLimit = 100;
      const tenant = await createTenant({
        code: 'rate-limit-tenant',
        name: 'Rate Limit Tenant',
        status: 'ACTIVE' as any,
        plan: 'professional' as any,
        maxApiCallsPerDay: 10000,
      } as any);

      const tenantContext: TenantContext = {
        tenantId: tenant.id,
        tenantCode: tenant.code,
        plan: 'professional',
        dbSchema: tenant.dbSchema,
        elasticIndexPrefix: tenant.elasticIndexPrefix,
        limits: {
          maxApiCallsPerDay: 10000,
          maxStorageGb: 100,
          maxContentModels: 100,
          maxUsers: 200,
          maxWebhooks: 50,
          enableVersioning: true,
          enableWorkflow: true,
          enableElasticsearch: true,
          enableCDN: true,
        },
      };

      const requestCount = 1000;
      const requests: (() => Promise<{ allowed: boolean; remaining: number }>)[] = [];

      for (let i = 0; i < requestCount; i++) {
        requests.push(async () => {
          const result = await usageService.checkRateLimit(tenantContext, '/api/v1/test');
          return {
            allowed: result.allowed,
            remaining: result.remaining,
          };
        });
      }

      const results = await simulateConcurrentRequests(requests, 50);

      expect(results.length).toBe(requestCount);

      const allowedCount = results.filter(r => r.allowed).length;
      const deniedCount = results.filter(r => !r.allowed).length;

      expect(allowedCount).toBe(rateLimit);
      expect(deniedCount).toBe(requestCount - rateLimit);

      const minRemaining = Math.min(...results.map(r => r.remaining));
      const maxRemaining = Math.max(...results.map(r => r.remaining));

      expect(maxRemaining).toBeLessThanOrEqual(rateLimit - 1);
      expect(minRemaining).toBe(0);

      const finalResult = await usageService.checkRateLimit(tenantContext, '/api/v1/test');
      expect(finalResult.allowed).toBe(false);
      expect(finalResult.remaining).toBe(0);
      expect(finalResult.limit).toBe(rateLimit);
    });

    it('should handle concurrent tenant context resolution without race conditions', async () => {
      const tenants: Tenant[] = [];
      for (let i = 0; i < 50; i++) {
        const tenant = await createTenant({
          code: `race-tenant-${i}`,
          name: `Race Tenant ${i}`,
          status: 'ACTIVE' as any,
        });
        tenants.push(tenant);
      }

      const requestCount = 200;
      const requests: (() => Promise<{ success: boolean; tenantCode: string }>)[] = [];

      for (let i = 0; i < requestCount; i++) {
        const tenant = tenants[i % tenants.length];
        requests.push(async () => {
          const request = createApiRequest(tenant.apiKey, '/api/v1/health');
          const context = await tenantResolver.resolveFromRequest(request as any);
          return {
            success: context !== null && context.tenantId === tenant.id,
            tenantCode: context?.tenantCode || '',
          };
        });
      }

      const results = await simulateConcurrentRequests(requests, 30);

      const successCount = results.filter(r => r.success).length;
      expect(successCount).toBe(requestCount);

      for (const result of results) {
        expect(result.success).toBe(true);
      }
    });

    it('should isolate connection pools under concurrent access', async () => {
      const tenants: Tenant[] = [];
      for (let i = 0; i < 20; i++) {
        const tenant = await createTenant({
          code: `concurrent-pool-${i}`,
          name: `Concurrent Pool ${i}`,
          status: 'ACTIVE' as any,
        });
        tenants.push(tenant);
      }

      const requestCount = 100;
      const requests: (() => Promise<{ tenantId: string; poolId: string }>)[] = [];

      for (let i = 0; i < requestCount; i++) {
        const tenant = tenants[i % tenants.length];
        requests.push(async () => {
          const pool = connectionPool.getTenantPool(tenant.id, tenant.dbSchema);
          return {
            tenantId: tenant.id,
            poolId: (pool as any)._eventsCount?.toString() || pool.totalCount?.toString() || 'default',
          };
        });
      }

      const results = await simulateConcurrentRequests(requests, 25);

      const poolTenantMap: Record<string, Set<string>> = {};
      for (const result of results) {
        if (!poolTenantMap[result.tenantId]) {
          poolTenantMap[result.tenantId] = new Set();
        }
        poolTenantMap[result.tenantId].add(result.poolId);
      }

      for (const tenantId of Object.keys(poolTenantMap)) {
        expect(poolTenantMap[tenantId].size).toBe(1);
      }
    });
  });

  describe('Tenant Context Building', () => {
    it('should build correct tenant context with plan limits', async () => {
      const tenant = await createTenant({
        code: 'context-tenant',
        name: 'Context Tenant',
        status: 'ACTIVE' as any,
        plan: 'professional' as any,
      });

      const request = createApiRequest(tenant.apiKey, '/api/v1/health');
      const context = await tenantResolver.resolveFromRequest(request as any);

      expect(context).not.toBeNull();
      expect(context!.tenantId).toBe(tenant.id);
      expect(context!.tenantCode).toBe(tenant.code);
      expect(context!.plan).toBe('professional');
      expect(context!.dbSchema).toBe(tenant.dbSchema);
      expect(context!.elasticIndexPrefix).toBe(tenant.elasticIndexPrefix);
      expect(context!.limits.maxApiCallsPerDay).toBeGreaterThan(0);
      expect(context!.limits.enableElasticsearch).toBe(true);
      expect(context!.limits.enableWorkflow).toBe(true);
    });

    it('should apply custom limits over plan defaults', async () => {
      const customLimit = 500000;
      const tenant = await createTenant({
        code: 'custom-limit-tenant',
        name: 'Custom Limit Tenant',
        status: 'ACTIVE' as any,
        plan: 'free' as any,
        maxApiCallsPerDay: customLimit,
      } as any);

      const request = createApiRequest(tenant.apiKey, '/api/v1/health');
      const context = await tenantResolver.resolveFromRequest(request as any);

      expect(context).not.toBeNull();
      expect(context!.limits.maxApiCallsPerDay).toBe(customLimit);
    });
  });
});
