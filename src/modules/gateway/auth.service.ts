import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import type { IAuthService } from '@ports/index';
import type { UserPrincipal } from '@apptypes/index';
import { config } from '@config/index';
import { rootLogger } from '@modules/logging';

const DEFAULT_SALT_ROUNDS = 12;
const TOKEN_TYPE = 'Bearer';

interface DecodedToken {
  sub: string;
  username: string;
  roles: string[];
  permissions: string[];
  tenant_id: string;
}

export class AuthService implements IAuthService {
  private readonly logger = rootLogger.child({ module: 'AuthService' });

  async authenticate(token: string): Promise<UserPrincipal | null> {
    try {
      const decoded = this.verifyAndDecodeToken(token);
      return this.buildPrincipalFromToken(decoded);
    } catch (error) {
      this.handleAuthFailure(error);
      return null;
    }
  }

  authorize(principal: UserPrincipal, permission: string): boolean {
    if (this.isAdmin(principal)) {
      return true;
    }

    const hasPermission = principal.permissions.includes(permission);
    if (!hasPermission) {
      this.logger.warn('Authorization failed', {
        user_id: principal.user_id,
        permission,
      });
    }
    return hasPermission;
  }

  async generateToken(principal: UserPrincipal): Promise<string> {
    const payload = this.buildTokenPayload(principal);
    return jwt.sign(payload, config.jwt.secret as jwt.Secret, {
      expiresIn: config.jwt.expiresIn,
      issuer: config.jwt.issuer,
    } as jwt.SignOptions);
  }

  async hashPassword(password: string): Promise<string> {
    return bcrypt.hash(password, DEFAULT_SALT_ROUNDS);
  }

  async verifyPassword(password: string, hash: string): Promise<boolean> {
    return bcrypt.compare(password, hash);
  }

  private verifyAndDecodeToken(token: string): DecodedToken {
    const decoded = jwt.verify(token, config.jwt.secret, {
      issuer: config.jwt.issuer,
    }) as DecodedToken;

    return {
      sub: decoded.sub,
      username: decoded.username,
      roles: decoded.roles || [],
      permissions: decoded.permissions || [],
      tenant_id: decoded.tenant_id,
    };
  }

  private buildPrincipalFromToken(decoded: DecodedToken): UserPrincipal {
    return {
      user_id: decoded.sub,
      username: decoded.username,
      roles: decoded.roles,
      permissions: decoded.permissions,
      tenant_id: decoded.tenant_id,
    };
  }

  private buildTokenPayload(principal: UserPrincipal): jwt.JwtPayload {
    return {
      sub: principal.user_id,
      username: principal.username,
      roles: principal.roles,
      permissions: principal.permissions,
      tenant_id: principal.tenant_id,
    };
  }

  private isAdmin(principal: UserPrincipal): boolean {
    return principal.roles.includes('admin');
  }

  private handleAuthFailure(error: unknown): void {
    this.logger.warn('Authentication failed', {
      error: (error as Error).message,
    });
  }
}

export const authService = new AuthService();
