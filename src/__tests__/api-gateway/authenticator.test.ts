import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import { Authenticator } from '../../api-gateway/authenticator';

jest.mock('jsonwebtoken');
jest.mock('bcryptjs');

const TEST_JWT_SECRET = 'test-secret-key-for-testing-only';
const TEST_JWT_EXPIRES_IN = 3600;

describe('Authenticator', () => {
  let authenticator: Authenticator;

  beforeEach(() => {
    jest.clearAllMocks();
    authenticator = new Authenticator(TEST_JWT_SECRET, TEST_JWT_EXPIRES_IN);
  });

  afterEach(() => {
    authenticator.destroy();
  });

  describe('registerUser', () => {
    it('should register a new user successfully', async () => {
      const mockHash = 'hashed_password_123';
      (bcrypt.hash as jest.Mock).mockResolvedValue(mockHash);

      const user = await authenticator.registerUser(
        'testuser',
        'test@example.com',
        'password123',
        ['user'],
        ['read']
      );

      expect(user).toBeDefined();
      expect(user.id).toBeDefined();
      expect(user.username).toBe('testuser');
      expect(user.email).toBe('test@example.com');
      expect(user.roles).toEqual(['user']);
      expect(user.permissions).toEqual(['read']);
      expect(bcrypt.hash).toHaveBeenCalledWith('password123', 10);
    });

    it('should throw for username too short', async () => {
      await expect(
        authenticator.registerUser('ab', 'test@example.com', 'password123', ['user'], ['read'])
      ).rejects.toThrow('Username must be at least 3 characters long');
    });

    it('should throw for invalid email format', async () => {
      await expect(
        authenticator.registerUser('testuser', 'invalid-email', 'password123', ['user'], ['read'])
      ).rejects.toThrow('Invalid email format');
    });

    it('should throw for password too short', async () => {
      await expect(
        authenticator.registerUser('testuser', 'test@example.com', '123', ['user'], ['read'])
      ).rejects.toThrow('Password must be at least 6 characters long');
    });

    it('should throw for empty username', async () => {
      await expect(
        authenticator.registerUser('', 'test@example.com', 'password123', ['user'], ['read'])
      ).rejects.toThrow('Username must be at least 3 characters long');
    });

    it('should throw error for empty password', async () => {
      await expect(
        authenticator.registerUser('testuser', 'test@example.com', '', ['user'], ['read'])
      ).rejects.toThrow('Password must be at least 6 characters long');
    });

    it('should handle users with multiple roles', async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      const user = await authenticator.registerUser(
        'adminuser',
        'admin@example.com',
        'adminpass',
        ['admin', 'user', 'moderator'],
        ['read', 'write', 'delete', 'manage']
      );

      expect(user.roles).toHaveLength(3);
      expect(user.permissions).toHaveLength(4);
    });

    it('should generate unique user IDs', async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      const user1 = await authenticator.registerUser('user1', 'u1@e.com', 'pass123', [], []);
      const user2 = await authenticator.registerUser('user2', 'u2@e.com', 'pass123', [], []);

      expect(user1.id).not.toBe(user2.id);
    });

    it('should throw error if service is destroyed', async () => {
      authenticator.destroy();
      await expect(
        authenticator.registerUser('test', 't@e.com', 'password', [], [])
      ).rejects.toThrow('Authenticator has been destroyed');
    });
  });

  describe('authenticate', () => {
    beforeEach(async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hashed_password');
      await authenticator.registerUser('testuser', 'test@example.com', 'password123', ['user'], ['read']);
    });

    it('should authenticate with valid username and password', async () => {
      (bcrypt.compare as jest.Mock).mockResolvedValue(true);
      (jwt.sign as jest.Mock).mockReturnValue('valid_token');

      const result = await authenticator.authenticate('testuser', 'password123');

      expect(result).not.toBeNull();
      expect(result?.accessToken).toBe('valid_token');
      expect(result?.refreshToken).toBeDefined();
      expect(result?.expiresIn).toBe(TEST_JWT_EXPIRES_IN);
      expect(jwt.sign).toHaveBeenCalled();
    });

    it('should authenticate with email instead of username', async () => {
      (bcrypt.compare as jest.Mock).mockResolvedValue(true);
      (jwt.sign as jest.Mock).mockReturnValue('valid_token');

      const result = await authenticator.authenticate('test@example.com', 'password123');

      expect(result).not.toBeNull();
    });

    it('should return null for non-existent user', async () => {
      const result = await authenticator.authenticate('nonexistent', 'password123');
      expect(result).toBeNull();
    });

    it('should return null for wrong password', async () => {
      (bcrypt.compare as jest.Mock).mockResolvedValue(false);
      const result = await authenticator.authenticate('testuser', 'wrongpassword');
      expect(result).toBeNull();
    });

    it('should return null for empty username', async () => {
      const result = await authenticator.authenticate('', 'password123');
      expect(result).toBeNull();
    });

    it('should return null for empty password', async () => {
      const result = await authenticator.authenticate('testuser', '');
      expect(result).toBeNull();
    });

    it('should generate unique refresh tokens', async () => {
      (bcrypt.compare as jest.Mock).mockResolvedValue(true);
      (jwt.sign as jest.Mock).mockReturnValue('token');

      const result1 = await authenticator.authenticate('testuser', 'password123');
      const result2 = await authenticator.authenticate('testuser', 'password123');

      expect(result1?.refreshToken).not.toBe(result2?.refreshToken);
    });
  });

  describe('validateToken', () => {
    beforeEach(async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      await authenticator.registerUser('testuser', 'test@example.com', 'password123', ['user'], ['read']);
    });

    it('should validate a valid token', async () => {
      const mockDecoded = {
        userId: 'some-user-id',
        username: 'testuser',
        roles: ['user'],
        permissions: ['read'],
      };
      (jwt.verify as jest.Mock).mockReturnValue(mockDecoded);

      // 先注册一个用户
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      const user = await authenticator.registerUser('validuser', 'v@e.com', 'password123', [], []);

      // 修改mock返回实际注册的用户ID
      (jwt.verify as jest.Mock).mockReturnValue({
        ...mockDecoded,
        userId: user.id,
      });

      const result = await authenticator.validateToken('valid.token.here');

      expect(result).not.toBeNull();
      expect(result?.authenticated).toBe(true);
      expect(result?.user.id).toBe(user.id);
    });

    it('should return null for invalid token', async () => {
      (jwt.verify as jest.Mock).mockImplementation(() => {
        throw new Error('Invalid token');
      });

      const result = await authenticator.validateToken('invalid.token');
      expect(result).toBeNull();
    });

    it('should return null for expired token', async () => {
      (jwt.verify as jest.Mock).mockImplementation(() => {
        throw new Error('Token expired');
      });

      const result = await authenticator.validateToken('expired.token');
      expect(result).toBeNull();
    });

    it('should return null if user does not exist', async () => {
      (jwt.verify as jest.Mock).mockReturnValue({
        userId: 'non-existent-id',
        username: 'ghost',
        roles: [],
        permissions: [],
      });

      const result = await authenticator.validateToken('token.for.ghost');
      expect(result).toBeNull();
    });

    it('should return null for empty token', async () => {
      const result = await authenticator.validateToken('');
      expect(result).toBeNull();
    });
  });

  describe('refreshToken', () => {
    it('should refresh token with valid refresh token', async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      (jwt.sign as jest.Mock).mockReturnValue('new_access_token');

      const user = await authenticator.registerUser('testuser', 't@e.com', 'password123', [], []);

      // 手动注入一个有效的refresh token
      const refreshToken = 'valid-refresh-token';
      (authenticator as any).refreshTokens.set(refreshToken, {
        userId: user.id,
        expiresAt: Date.now() + 100000,
      });

      const result = await authenticator.refreshToken(refreshToken);
      expect(result?.accessToken).toBe('new_access_token');
      expect(result?.refreshToken).toBeDefined();
      expect(result?.expiresIn).toBe(TEST_JWT_EXPIRES_IN);
    });

    it('should return null for expired refresh token', async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      const user = await authenticator.registerUser('testuser', 't@e.com', 'password123', [], []);

      const refreshToken = 'expired-refresh-token';
      (authenticator as any).refreshTokens.set(refreshToken, {
        userId: user.id,
        expiresAt: Date.now() - 1000,
      });

      const result = await authenticator.refreshToken(refreshToken);
      expect(result).toBeNull();
    });

    it('should return null for invalid refresh token', async () => {
      const result = await authenticator.refreshToken('non-existent-token');
      expect(result).toBeNull();
    });
  });

  describe('checkRoles', () => {
    it('should return true if user has all required roles', () => {
      const auth = {
        user: {
          id: '1',
          username: 'test',
          email: 't@e.com',
          roles: ['admin', 'user', 'moderator'],
          permissions: [],
        },
        token: 'token',
        authenticated: true,
      };

      expect(authenticator.checkRoles(auth, ['admin'])).toBe(true);
      expect(authenticator.checkRoles(auth, ['admin', 'user'])).toBe(true);
      expect(authenticator.checkRoles(auth, ['admin', 'user', 'moderator'])).toBe(true);
    });

    it('should return false if user missing required roles', () => {
      const auth = {
        user: {
          id: '1',
          username: 'test',
          email: 't@e.com',
          roles: ['user'],
          permissions: [],
        },
        token: 'token',
        authenticated: true,
      };

      expect(authenticator.checkRoles(auth, ['admin'])).toBe(false);
      expect(authenticator.checkRoles(auth, ['admin', 'user'])).toBe(false);
    });

    it('should return true for empty required roles', () => {
      const auth = {
        user: { id: '1', username: 'test', email: 't@e.com', roles: [], permissions: [] },
        token: 'token',
        authenticated: true,
      };
      expect(authenticator.checkRoles(auth, [])).toBe(true);
    });
  });

  describe('checkPermissions', () => {
    it('should return true if user has all required permissions', () => {
      const auth = {
        user: {
          id: '1',
          username: 'test',
          email: 't@e.com',
          roles: [],
          permissions: ['read', 'write', 'delete'],
        },
        token: 'token',
        authenticated: true,
      };

      expect(authenticator.checkPermissions(auth, ['read'])).toBe(true);
      expect(authenticator.checkPermissions(auth, ['read', 'write'])).toBe(true);
    });

    it('should return false if user missing required permissions', () => {
      const auth = {
        user: {
          id: '1',
          username: 'test',
          email: 't@e.com',
          roles: [],
          permissions: ['read'],
        },
        token: 'token',
        authenticated: true,
      };

      expect(authenticator.checkPermissions(auth, ['delete'])).toBe(false);
      expect(authenticator.checkPermissions(auth, ['read', 'delete'])).toBe(false);
    });
  });

  describe('authenticateRequest', () => {
    it('should authenticate with valid Bearer token', async () => {
      (bcrypt.hash as jest.Mock).mockResolvedValue('hash');
      const user = await authenticator.registerUser('test', 't@e.com', 'password123', [], []);

      (jwt.verify as jest.Mock).mockReturnValue({
        userId: user.id,
        username: 'test',
        roles: [],
        permissions: [],
      });

      const result = await authenticator.authenticateRequest('Bearer valid.token');
      expect(result.success).toBe(true);
      expect(result.auth).toBeDefined();
    });

    it('should fail without Bearer prefix', async () => {
      const result = await authenticator.authenticateRequest('Basic some-auth');
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe('MISSING_AUTH_HEADER');
    });

    it('should fail with empty authorization header', async () => {
      const result = await authenticator.authenticateRequest(undefined);
      expect(result.success).toBe(false);
    });

    it('should fail with invalid token', async () => {
      const result = await authenticator.authenticateRequest('Bearer invalid.token');
      expect(result.success).toBe(false);
      expect(result.errorCode).toBe('INVALID_TOKEN');
    });
  });

  describe('logout', () => {
    it('should remove refresh token on logout', () => {
      const refreshToken = 'token-to-logout';
      (authenticator as any).refreshTokens.set(refreshToken, {
        userId: '1',
        expiresAt: Date.now() + 10000,
      });

      authenticator.logout(refreshToken);
      expect((authenticator as any).refreshTokens.has(refreshToken)).toBe(false);
    });

    it('should not throw for non-existent token', () => {
      expect(() => authenticator.logout('non-existent')).not.toThrow();
    });
  });
});
