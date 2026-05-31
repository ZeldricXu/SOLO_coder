import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { User, AuthToken, AuthCredentials, JwtPayload, PermissionCheck, Tenant } from '../../types/auth';
import { generateId, getCurrentTimestamp, generateRandomString } from '../../common/utils';
import { AuthenticationError, AuthorizationError, NotFoundError } from '../../common/errors';

export class AuthService {
  private users: Map<string, User>;
  private tenants: Map<string, Tenant>;
  private refreshTokens: Map<string, string>;
  private jwtSecret: string;
  private jwtExpiresIn: string;

  constructor(jwtSecret: string, jwtExpiresIn: string = '24h') {
    this.users = new Map();
    this.tenants = new Map();
    this.refreshTokens = new Map();
    this.jwtSecret = jwtSecret;
    this.jwtExpiresIn = jwtExpiresIn;
  }

  async createUser(username: string, email: string, password: string, tenantId: string, roles: string[] = ['user']): Promise<User> {
    const passwordHash = await bcrypt.hash(password, 10);
    const now = getCurrentTimestamp();

    const user: User = {
      id: generateId('user'),
      username,
      email,
      passwordHash,
      tenantId,
      roles,
      permissions: [],
      status: 'active',
      createdAt: now,
      updatedAt: now
    };

    this.users.set(user.id, user);
    return user;
  }

  async createTenant(name: string, plan: Tenant['plan'] = 'basic'): Promise<Tenant> {
    const now = getCurrentTimestamp();
    const tenant: Tenant = {
      id: generateId('tenant'),
      name,
      plan,
      status: 'active',
      settings: {
        rateLimit: {
          windowMs: 60000,
          maxRequests: plan === 'enterprise' ? 10000 : plan === 'pro' ? 1000 : 100
        },
        maxUsers: plan === 'enterprise' ? 1000 : plan === 'pro' ? 100 : 10,
        maxStorageGb: plan === 'enterprise' ? 1000 : plan === 'pro' ? 100 : 10,
        features: ['core']
      },
      createdAt: now,
      updatedAt: now
    };

    this.tenants.set(tenant.id, tenant);
    return tenant;
  }

  async authenticate(credentials: AuthCredentials): Promise<AuthToken> {
    const user = this.findUserByUsername(credentials.username);
    if (!user) {
      throw new AuthenticationError('用户名或密码错误');
    }

    if (user.status !== 'active') {
      throw new AuthenticationError('用户账户已被禁用');
    }

    const passwordValid = await bcrypt.compare(credentials.password, user.passwordHash);
    if (!passwordValid) {
      throw new AuthenticationError('用户名或密码错误');
    }

    const tenant = this.tenants.get(user.tenantId);
    if (!tenant || tenant.status !== 'active') {
      throw new AuthenticationError('租户账户已被禁用');
    }

    user.lastLoginAt = getCurrentTimestamp();
    user.updatedAt = getCurrentTimestamp();
    this.users.set(user.id, user);

    return this.generateTokens(user);
  }

  private generateTokens(user: User): AuthToken {
    const payload: Omit<JwtPayload, 'iat' | 'exp' | 'jti'> = {
      sub: user.id,
      username: user.username,
      tenantId: user.tenantId,
      roles: user.roles,
      permissions: user.permissions
    };

    const accessToken = jwt.sign(payload, this.jwtSecret, {
      expiresIn: this.jwtExpiresIn,
      jwtid: generateId('jti')
    });

    const refreshToken = generateRandomString(64);
    this.refreshTokens.set(refreshToken, user.id);

    return {
      accessToken,
      refreshToken,
      tokenType: 'Bearer',
      expiresIn: this.parseExpiresIn(this.jwtExpiresIn),
      issuedAt: getCurrentTimestamp()
    };
  }

  private parseExpiresIn(expiresIn: string): number {
    const match = expiresIn.match(/^(\d+)([smhd])$/);
    if (!match) return 86400;

    const value = parseInt(match[1]);
    const unit = match[2];

    switch (unit) {
      case 's': return value;
      case 'm': return value * 60;
      case 'h': return value * 3600;
      case 'd': return value * 86400;
      default: return 86400;
    }
  }

  verifyToken(token: string): JwtPayload {
    try {
      const payload = jwt.verify(token, this.jwtSecret) as JwtPayload;
      return payload;
    } catch (error) {
      if (error instanceof jwt.TokenExpiredError) {
        throw new AuthenticationError('令牌已过期');
      }
      if (error instanceof jwt.JsonWebTokenError) {
        throw new AuthenticationError('无效的令牌');
      }
      throw new AuthenticationError('令牌验证失败');
    }
  }

  async refreshToken(refreshToken: string): Promise<AuthToken> {
    const userId = this.refreshTokens.get(refreshToken);
    if (!userId) {
      throw new AuthenticationError('无效的刷新令牌');
    }

    const user = this.users.get(userId);
    if (!user || user.status !== 'active') {
      throw new AuthenticationError('用户不存在或已被禁用');
    }

    this.refreshTokens.delete(refreshToken);
    return this.generateTokens(user);
  }

  revokeRefreshToken(refreshToken: string): void {
    this.refreshTokens.delete(refreshToken);
  }

  hasPermission(user: JwtPayload, permissionCheck: PermissionCheck): boolean {
    const { action, resource } = permissionCheck;

    if (user.roles.includes('admin') || user.roles.includes('super_admin')) {
      return true;
    }

    const permission = `${action}:${resource}`;
    if (user.permissions.includes(permission)) {
      return true;
    }

    const rolePermissions = this.getRolePermissions(user.roles);
    return rolePermissions.includes(permission);
  }

  private getRolePermissions(roles: string[]): string[] {
    const rolePermissionMap: Record<string, string[]> = {
      user: ['read:resource', 'create:resource'],
      editor: ['read:resource', 'create:resource', 'update:resource'],
      admin: ['read:resource', 'create:resource', 'update:resource', 'delete:resource'],
      super_admin: ['*:*']
    };

    const permissions: string[] = [];
    for (const role of roles) {
      const rolePerms = rolePermissionMap[role] || [];
      permissions.push(...rolePerms);
    }

    return [...new Set(permissions)];
  }

  checkPermission(user: JwtPayload, permissionCheck: PermissionCheck): void {
    if (!this.hasPermission(user, permissionCheck)) {
      throw new AuthorizationError(`缺少权限: ${permissionCheck.action}:${permissionCheck.resource}`);
    }
  }

  findUserById(userId: string): User | undefined {
    return this.users.get(userId);
  }

  findUserByUsername(username: string): User | undefined {
    return Array.from(this.users.values()).find(u => u.username === username);
  }

  getTenant(tenantId: string): Tenant {
    const tenant = this.tenants.get(tenantId);
    if (!tenant) {
      throw new NotFoundError(`租户不存在: ${tenantId}`);
    }
    return tenant;
  }

  updateUserRoles(userId: string, roles: string[]): User {
    const user = this.users.get(userId);
    if (!user) {
      throw new NotFoundError(`用户不存在: ${userId}`);
    }

    user.roles = roles;
    user.updatedAt = getCurrentTimestamp();
    this.users.set(userId, user);
    return user;
  }

  updateUserPermissions(userId: string, permissions: string[]): User {
    const user = this.users.get(userId);
    if (!user) {
      throw new NotFoundError(`用户不存在: ${userId}`);
    }

    user.permissions = permissions;
    user.updatedAt = getCurrentTimestamp();
    this.users.set(userId, user);
    return user;
  }

  hashPassword(password: string): Promise<string> {
    return bcrypt.hash(password, 10);
  }

  verifyPassword(password: string, hash: string): Promise<boolean> {
    return bcrypt.compare(password, hash);
  }
}
