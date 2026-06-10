import { Request, Response, NextFunction } from 'express';
import { IncomingMessage } from 'http';
import { createLogger } from '../utils/logger';

const logger = createLogger('AuthMiddleware');

export interface AuthPayload {
  userId: string;
  token: string;
  roomId?: string;
  issuedAt: number;
}

const VALID_TOKENS = new Set<string>([
  'dev-token-12345',
  'dev-token-67890'
]);

export function validateToken(token: string): boolean {
  if (!token || typeof token !== 'string') {
    return false;
  }
  if (process.env.NODE_ENV === 'development') {
    return true;
  }
  return VALID_TOKENS.has(token);
}

export function extractTokenFromHeader(header: string | undefined): string | null {
  if (!header) {
    return null;
  }
  const parts = header.split(' ');
  if (parts.length === 2 && parts[0].toLowerCase() === 'bearer') {
    return parts[1];
  }
  return header;
}

export function extractTokenFromQuery(query: string | string[] | undefined): string | null {
  if (!query) {
    return null;
  }
  if (Array.isArray(query)) {
    return query[0] || null;
  }
  return query;
}

export function parseAuthToken(token: string): AuthPayload | null {
  try {
    const decoded = Buffer.from(token, 'base64').toString('utf-8');
    const payload = JSON.parse(decoded) as Partial<AuthPayload>;
    if (!payload.userId || !payload.token) {
      return null;
    }
    return {
      userId: payload.userId,
      token: payload.token,
      roomId: payload.roomId,
      issuedAt: payload.issuedAt || Date.now()
    };
  } catch {
    return null;
  }
}

export function httpAuthMiddleware(req: Request, res: Response, next: NextFunction): void {
  const token = extractTokenFromHeader(req.headers.authorization);
  if (!token) {
    logger.warn('HTTP request missing auth token', { path: req.path });
    res.status(401).json({ error: 'Missing authentication token' });
    return;
  }
  if (!validateToken(token)) {
    logger.warn('HTTP request with invalid token', { path: req.path });
    res.status(401).json({ error: 'Invalid authentication token' });
    return;
  }
  const payload = parseAuthToken(token);
  if (payload) {
    (req as Request & { auth?: AuthPayload }).auth = payload;
  }
  next();
}

export function wsAuthMiddleware(request: IncomingMessage): { authorized: boolean; userId?: string; error?: string } {
  const url = request.url || '';
  try {
    const urlObj = new URL(url, `http://${request.headers.host}`);
    const tokenParam = urlObj.searchParams.get('token') ?? undefined;
    const token = extractTokenFromQuery(tokenParam);
    const userId = urlObj.searchParams.get('userId') || undefined;
    if (!token) {
      logger.warn('WebSocket connection missing auth token');
      return { authorized: false, error: 'Missing authentication token' };
    }
    if (!validateToken(token)) {
      logger.warn('WebSocket connection with invalid token');
      return { authorized: false, error: 'Invalid authentication token' };
    }
    return { authorized: true, userId };
  } catch (error) {
    logger.error('WebSocket auth error', { error: error instanceof Error ? error.message : String(error) });
    return { authorized: false, error: 'Authentication failed' };
  }
}
