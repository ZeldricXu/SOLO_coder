import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { v4 as uuidv4 } from 'uuid';
import { User, AuthToken } from '../types';
import { AuthContext, AuthenticationResult } from './types';
import { createSuccessResult, createErrorResult, AuthenticationError, ValidationError } from '../common/errors';
import { BaseService } from '../common/base-service';

export class Authenticator extends BaseService {
  private readonly users: Map<string, User & { passwordHash: string }> = new Map();
  private readonly refreshTokens: Map<string, { userId: string; expiresAt: number }> = new Map();

  constructor(
    private readonly jwtSecret: string,
    private readonly jwtExpiresIn: number = 3600
  ) {
    super('Authenticator');
  }

  async registerUser(
    username: string,
    email: string,
    password: string,
    roles: string[],
    permissions: string[]
  ): Promise<User> {
    this.assertNotDestroyed();
    this.validateRegistrationInput(username, email, password);

    const passwordHash = await bcrypt.hash(password, 10);
    const user: User & { passwordHash: string } = {
      id: uuidv4(),
      username,
      email,
      roles,
      permissions,
      passwordHash,
    };

    this.users.set(user.id, user);
    return user;
  }

  async authenticate(username: string, password: string): Promise<AuthToken | null> {
    this.assertNotDestroyed();

    const user = this.findUserByIdentifier(username);
    if (!user) return null;

    const isValid = await bcrypt.compare(password, user.passwordHash);
    if (!isValid) return null;

    return this.generateAuthTokens(user);
  }

  async validateToken(token: string): Promise<AuthContext | null> {
    this.assertNotDestroyed();

    try {
      const decoded = jwt.verify(token, this.jwtSecret) as {
        userId: string;
        username: string;
        roles: string[];
        permissions: string[];
      };

      const user = this.users.get(decoded.userId);
      if (!user) return null;

      return {
        user,
        token,
        authenticated: true,
      };
    } catch {
      return null;
    }
  }

  async refreshToken(refreshToken: string): Promise<AuthToken | null> {
    this.assertNotDestroyed();

    const tokenInfo = this.refreshTokens.get(refreshToken);
    if (!tokenInfo || this.isTokenExpired(tokenInfo.expiresAt)) {
      this.refreshTokens.delete(refreshToken);
      return null;
    }

    const user = this.users.get(tokenInfo.userId);
    if (!user) return null;

    return this.generateAuthTokens(user);
  }

  logout(refreshToken: string): void {
    this.assertNotDestroyed();
    this.refreshTokens.delete(refreshToken);
  }

  checkRoles(auth: AuthContext, requiredRoles: string[]): boolean {
    return requiredRoles.every(role => auth.user.roles.includes(role));
  }

  checkPermissions(auth: AuthContext, requiredPermissions: string[]): boolean {
    return requiredPermissions.every(perm => auth.user.permissions.includes(perm));
  }

  async authenticateRequest(
    authorizationHeader: string | undefined
  ): Promise<AuthenticationResult> {
    if (!authorizationHeader || !authorizationHeader.startsWith('Bearer ')) {
      return createErrorResult('Missing or invalid authorization header', 'MISSING_AUTH_HEADER');
    }

    const token = authorizationHeader.slice(7);
    const auth = await this.validateToken(token);

    if (!auth) {
      return createErrorResult('Invalid or expired token', 'INVALID_TOKEN');
    }

    return { success: true, auth };
  }

  private findUserByIdentifier(identifier: string): (User & { passwordHash: string }) | undefined {
    return Array.from(this.users.values()).find(
      u => u.username === identifier || u.email === identifier
    );
  }

  private generateAuthTokens(user: User): AuthToken {
    const accessToken = jwt.sign(
      {
        userId: user.id,
        username: user.username,
        roles: user.roles,
        permissions: user.permissions,
      },
      this.jwtSecret,
      { expiresIn: this.jwtExpiresIn }
    );

    const refreshToken = uuidv4();
    this.refreshTokens.set(refreshToken, {
      userId: user.id,
      expiresAt: Date.now() + this.jwtExpiresIn * 2 * 1000,
    });

    return {
      accessToken,
      refreshToken,
      expiresIn: this.jwtExpiresIn,
    };
  }

  private isTokenExpired(expiresAt: number): boolean {
    return expiresAt < Date.now();
  }

  private validateRegistrationInput(
    username: string,
    email: string,
    password: string
  ): void {
    if (!username || username.length < 3) {
      throw new ValidationError('Username must be at least 3 characters long');
    }
    if (!email || !this.isValidEmail(email)) {
      throw new ValidationError('Invalid email format');
    }
    if (!password || password.length < 6) {
      throw new ValidationError('Password must be at least 6 characters long');
    }
  }

  private isValidEmail(email: string): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
  }
}
