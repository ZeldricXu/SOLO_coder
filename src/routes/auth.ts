import { Router, Request, Response } from 'express';
import { z } from 'zod';
import { apiGateway } from '../gateway';
import { logger } from '../logging';

const router = Router();

const LoginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
});

const CreateUserSchema = z.object({
  username: z.string().min(3),
  password: z.string().min(6),
  roles: z.array(z.string()).default(['user']),
  permissions: z.array(z.string()).default([]),
  tenant_id: z.string().default('default'),
});

router.post('/login', async (req: Request, res: Response) => {
  try {
    const body = LoginSchema.parse(req.body);
    const result = await apiGateway.authenticateUser(body.username, body.password);

    if (!result) {
      res.status(401).json({ code: 401, error: 'Invalid username or password' });
      return;
    }

    res.json({
      code: 200,
      data: {
        token: result.token,
        user: result.user,
      },
    });
  } catch (error) {
    logger.error('Login failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/users', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    if (!auth) {
      res.status(401).json({ code: 401, error: 'Unauthorized' });
      return;
    }

    const body = CreateUserSchema.parse(req.body);
    const user = apiGateway.createUser(
      body.username,
      body.password,
      body.roles,
      body.permissions,
      body.tenant_id
    );

    if (!user) {
      res.status(409).json({ code: 409, error: 'Username already exists' });
      return;
    }

    logger.info('User created via API', { username: body.username, created_by: auth.user_id });
    res.status(201).json({ code: 201, data: user });
  } catch (error) {
    logger.error('User creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/api-keys', (req: Request, res: Response) => {
  try {
    const auth = (req as unknown as { auth?: { user_id: string } }).auth;
    if (!auth) {
      res.status(401).json({ code: 401, error: 'Unauthorized' });
      return;
    }

    const { name, scopes, expires_at } = req.body;
    const expiresAt = expires_at ? new Date(expires_at) : undefined;

    const apiKey = apiGateway.createApiKey(auth.user_id, name || 'default', scopes || ['*'], expiresAt);

    res.status(201).json({ code: 201, data: apiKey });
  } catch (error) {
    logger.error('API key creation failed', { error: (error as Error).message });
    res.status(400).json({ code: 400, error: (error as Error).message });
  }
});

router.post('/logout', (req: Request, res: Response) => {
  const authHeader = req.headers.authorization;
  if (authHeader && authHeader.startsWith('Bearer ')) {
    const token = authHeader.slice(7);
    apiGateway.invalidateToken(token);
  }
  res.json({ code: 200, message: 'Logged out successfully' });
});

export default router;
