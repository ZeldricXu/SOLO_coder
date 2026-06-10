import { logger } from '@utils/logger';
import { config } from '@config/index';
import { TenantContext, DynamicApproverConfig } from '@types/index';

export type ApproverSourceType = 'static' | 'ldap' | 'org_api' | 'role_based';

export interface ApproverRole {
  role: string;
  source: ApproverSourceType;
  config?: Record<string, unknown>;
}

export interface ResolveApproversInput {
  tenantId: string;
  contentId: string;
  submittedBy: string;
  dynamicConfig: DynamicApproverConfig;
  contentData?: Record<string, unknown>;
}

export interface ApproverResolutionResult {
  approvers: string[];
  source: ApproverSourceType;
  resolvedFrom: string;
  cacheHit: boolean;
  warnings?: string[];
}

interface OrgApiResponse {
  userId: string;
  name: string;
  email: string;
  role: string;
}

interface LdapEntry {
  dn: string;
  cn: string;
  uid: string;
  manager?: string[];
  department?: string;
}

export class ApproverResolver {
  private cache = new Map<string, { approvers: string[]; timestamp: number }>();
  private readonly DEFAULT_CACHE_TTL = 300;

  async resolveApprovers(
    input: ResolveApproversInput
  ): Promise<ApproverResolutionResult> {
    const cacheKey = this.buildCacheKey(input);
    const cacheTtl = input.dynamicConfig.cacheTtlSeconds || this.DEFAULT_CACHE_TTL;

    const cached = this.cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < cacheTtl * 1000) {
      logger.debug(
        { cacheKey, approvers: cached.approvers },
        'Approver resolution cache hit'
      );
      return {
        approvers: cached.approvers,
        source: input.dynamicConfig.source,
        resolvedFrom: 'cache',
        cacheHit: true,
      };
    }

    const warnings: string[] = [];
    let approvers: string[] = [];
    let resolvedFrom: ApproverSourceType = 'static';

    try {
      switch (input.dynamicConfig.source) {
        case 'role_based':
          approvers = await this.resolveRoleBasedApprovers(input);
          resolvedFrom = 'role_based';
          break;

        case 'ldap':
          approvers = await this.resolveLdapApprovers(input);
          resolvedFrom = 'ldap';
          break;

        case 'org_api':
          approvers = await this.resolveOrgApiApprovers(input);
          resolvedFrom = 'org_api';
          break;

        case 'static':
          approvers = input.dynamicConfig.staticFallback;
          resolvedFrom = 'static';
          break;

        default:
          warnings.push(`Unknown approver source: ${input.dynamicConfig.source}, using static fallback`);
          approvers = input.dynamicConfig.staticFallback;
          resolvedFrom = 'static';
      }
    } catch (error) {
      logger.error(
        { error, source: input.dynamicConfig.source, submittedBy: input.submittedBy },
        'Failed to resolve approvers dynamically, falling back to static approvers'
      );
      warnings.push(`Dynamic resolution failed: ${(error as Error).message}`);
      approvers = input.dynamicConfig.staticFallback;
      resolvedFrom = 'static';
    }

    if (!approvers || approvers.length === 0) {
      logger.warn(
        { source: input.dynamicConfig.source, submittedBy: input.submittedBy },
        'No approvers found, using static fallback'
      );
      warnings.push('No approvers found from dynamic source, using static fallback');
      approvers = input.dynamicConfig.staticFallback;
      resolvedFrom = 'static';
    }

    approvers = [...new Set(approvers)];

    this.cache.set(cacheKey, {
      approvers,
      timestamp: Date.now(),
    });

    logger.debug(
      {
        cacheKey,
        approvers,
        source: input.dynamicConfig.source,
        resolvedFrom,
        warnings,
      },
      'Approvers resolved'
    );

    return {
      approvers,
      source: resolvedFrom,
      resolvedFrom,
      cacheHit: false,
      warnings: warnings.length > 0 ? warnings : undefined,
    };
  }

  private async resolveRoleBasedApprovers(
    input: ResolveApproversInput
  ): Promise<string[]> {
    const { roles = [] } = input.dynamicConfig;
    const approvers: string[] = [];

    for (const role of roles) {
      try {
        const roleApprovers = await this.resolveSingleRole(
          input.tenantId,
          role,
          input.submittedBy,
          input.contentData
        );
        approvers.push(...roleApprovers);
      } catch (error) {
        logger.error(
          { error, role: role.role, submittedBy: input.submittedBy },
          'Failed to resolve approvers for role'
        );
      }
    }

    return approvers;
  }

  private async resolveSingleRole(
    tenantId: string,
    role: ApproverRole,
    submittedBy: string,
    contentData?: Record<string, unknown>
  ): Promise<string[]> {
    switch (role.role) {
      case 'department_manager':
        return this.getDepartmentManager(tenantId, submittedBy);

      case 'direct_manager':
        return this.getDirectManager(tenantId, submittedBy);

      case 'division_head':
        return this.getDivisionHead(tenantId, submittedBy);

      case 'content_owner':
        return this.getContentOwner(tenantId, contentData);

      case 'hr_business_partner':
        return this.getHRBP(tenantId, submittedBy);

      case 'legal_counsel':
        return this.getLegalCounsel(tenantId);

      case 'finance_approver':
        return this.getFinanceApprover(tenantId);

      default:
        return this.resolveCustomRole(tenantId, role, submittedBy, contentData);
    }
  }

  private async getDirectManager(tenantId: string, userId: string): Promise<string[]> {
    logger.debug({ tenantId, userId }, 'Resolving direct manager');

    try {
      const orgResponse = await this.queryOrgApi(
        `/users/${userId}/manager`,
        tenantId
      );

      if (orgResponse && orgResponse.userId) {
        return [orgResponse.userId];
      }

      return [];
    } catch (error) {
      logger.error({ error, userId }, 'Failed to get direct manager from Org API');
      throw error;
    }
  }

  private async getDepartmentManager(tenantId: string, userId: string): Promise<string[]> {
    logger.debug({ tenantId, userId }, 'Resolving department manager');

    try {
      const userProfile = await this.queryOrgApi(`/users/${userId}`, tenantId);
      if (!userProfile || !userProfile.department) {
        return [];
      }

      const deptManagers = await this.queryOrgApi(
        `/departments/${userProfile.department}/managers`,
        tenantId
      );

      if (Array.isArray(deptManagers)) {
        return deptManagers.map((m: OrgApiResponse) => m.userId);
      }

      return [];
    } catch (error) {
      logger.error({ error, userId }, 'Failed to get department manager');
      throw error;
    }
  }

  private async getDivisionHead(tenantId: string, userId: string): Promise<string[]> {
    logger.debug({ tenantId, userId }, 'Resolving division head');

    try {
      const managers: string[] = [];
      let currentUserId = userId;
      let depth = 0;
      const maxDepth = 5;

      while (currentUserId && depth < maxDepth) {
        const manager = await this.queryOrgApi(
          `/users/${currentUserId}/manager`,
          tenantId
        );

        if (!manager || !manager.userId) break;

        managers.push(manager.userId);

        if (manager.role === 'division_head' || manager.role === 'vp') {
          break;
        }

        currentUserId = manager.userId;
        depth++;
      }

      return managers;
    } catch (error) {
      logger.error({ error, userId }, 'Failed to get division head');
      throw error;
    }
  }

  private async getContentOwner(
    tenantId: string,
    contentData?: Record<string, unknown>
  ): Promise<string[]> {
    if (contentData?.ownerId) {
      return [contentData.ownerId as string];
    }
    if (contentData?.createdBy) {
      return [contentData.createdBy as string];
    }
    return [];
  }

  private async getHRBP(tenantId: string, userId: string): Promise<string[]> {
    try {
      const user = await this.queryOrgApi(`/users/${userId}`, tenantId);
      if (!user?.department) return [];

      const hrbp = await this.queryOrgApi(
        `/departments/${user.department}/hrbp`,
        tenantId
      );

      if (Array.isArray(hrbp)) {
        return hrbp.map((h: OrgApiResponse) => h.userId);
      }

      return hrbp?.userId ? [hrbp.userId] : [];
    } catch (error) {
      logger.error({ error, userId }, 'Failed to get HRBP');
      throw error;
    }
  }

  private async getLegalCounsel(tenantId: string): Promise<string[]> {
    try {
      const legal = await this.queryOrgApi('/teams/legal/members', tenantId);
      if (Array.isArray(legal)) {
        return legal.map((l: OrgApiResponse) => l.userId);
      }
      return [];
    } catch (error) {
      logger.error({ error }, 'Failed to get legal counsel');
      throw error;
    }
  }

  private async getFinanceApprover(tenantId: string): Promise<string[]> {
    try {
      const finance = await this.queryOrgApi('/teams/finance/approvers', tenantId);
      if (Array.isArray(finance)) {
        return finance.map((f: OrgApiResponse) => f.userId);
      }
      return [];
    } catch (error) {
      logger.error({ error }, 'Failed to get finance approver');
      throw error;
    }
  }

  private async resolveCustomRole(
    tenantId: string,
    role: ApproverRole,
    userId: string,
    contentData?: Record<string, unknown>
  ): Promise<string[]> {
    logger.debug({ tenantId, role: role.role, userId }, 'Resolving custom role');

    if (role.config?.ldapQuery) {
      return this.queryLdap(role.config.ldapQuery as string, tenantId);
    }

    if (role.config?.apiEndpoint) {
      const response = await this.queryOrgApi(
        role.config.apiEndpoint as string,
        tenantId,
        { userId, contentData }
      );
      if (Array.isArray(response)) {
        return response.map((r: any) => r.userId || r.id || r);
      }
      return response?.userId ? [response.userId] : [];
    }

    return [];
  }

  private async resolveLdapApprovers(input: ResolveApproversInput): Promise<string[]> {
    const { dynamicConfig, submittedBy } = input;
    const ldapQuery = dynamicConfig.config?.ldapQuery as string;

    if (!ldapQuery) {
      throw new Error('LDAP query not configured');
    }

    const resolvedQuery = ldapQuery.replace('{{userId}}', submittedBy);
    return this.queryLdap(resolvedQuery, input.tenantId);
  }

  private async resolveOrgApiApprovers(input: ResolveApproversInput): Promise<string[]> {
    const { dynamicConfig, submittedBy, contentData } = input;
    const apiEndpoint = dynamicConfig.config?.apiEndpoint as string;

    if (!apiEndpoint) {
      throw new Error('Org API endpoint not configured');
    }

    const response = await this.queryOrgApi(apiEndpoint, input.tenantId, {
      userId: submittedBy,
      contentData,
    });

    if (Array.isArray(response)) {
      return response.map((r: any) => r.userId || r.id || r);
    }
    return response?.userId ? [response.userId] : [];
  }

  private async queryOrgApi(
    endpoint: string,
    tenantId: string,
    body?: Record<string, unknown>
  ): Promise<any> {
    const baseUrl = config.orgApiBaseUrl;
    if (!baseUrl) {
      throw new Error('Org API base URL not configured');
    }

    const url = `${baseUrl}${endpoint.startsWith('/') ? endpoint : `/${endpoint}`}`;
    const timeout = config.orgApiTimeout || 5000;

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeout);

    try {
      const response = await fetch(url, {
        method: body ? 'POST' : 'GET',
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-Id': tenantId,
          'Authorization': `Bearer ${config.orgApiToken}`,
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        throw new Error(`Org API returned ${response.status}: ${await response.text()}`);
      }

      return response.json();
    } catch (error) {
      clearTimeout(timeoutId);
      throw error;
    }
  }

  private async queryLdap(query: string, tenantId: string): Promise<string[]> {
    const ldapConfig = {
      url: config.ldapUrl,
      bindDn: config.ldapBindDn,
      bindPassword: config.ldapBindPassword,
      searchBase: config.ldapSearchBase,
    };

    if (!ldapConfig.url) {
      throw new Error('LDAP URL not configured');
    }

    logger.debug({ query, tenantId }, 'Executing LDAP query');

    try {
      const entries = await this.ldapSearch(ldapConfig, query);
      return entries.map(e => e.uid);
    } catch (error) {
      logger.error({ error, query }, 'LDAP query failed');
      throw error;
    }
  }

  private async ldapSearch(
    ldapConfig: any,
    filter: string
  ): Promise<LdapEntry[]> {
    logger.warn(
      { filter },
      'LDAP search is a stub in this implementation. Please integrate with a real LDAP client.'
    );
    return [];
  }

  invalidateCache(tenantId: string, userId?: string): void {
    const prefix = userId ? `${tenantId}:${userId}` : `${tenantId}:`;
    for (const key of this.cache.keys()) {
      if (key.startsWith(prefix)) {
        this.cache.delete(key);
      }
    }
    logger.debug({ tenantId, userId }, 'Approver cache invalidated');
  }

  private buildCacheKey(input: ResolveApproversInput): string {
    return [
      input.tenantId,
      input.contentId,
      input.submittedBy,
      input.dynamicConfig.source,
      JSON.stringify(input.dynamicConfig.roles || []),
    ].join(':');
  }
}

export const approverResolver = new ApproverResolver();
