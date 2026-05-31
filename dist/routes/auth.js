"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const zod_1 = require("zod");
const gateway_1 = require("../gateway");
const logging_1 = require("../logging");
const router = (0, express_1.Router)();
const LoginSchema = zod_1.z.object({
    username: zod_1.z.string().min(1),
    password: zod_1.z.string().min(1),
});
const CreateUserSchema = zod_1.z.object({
    username: zod_1.z.string().min(3),
    password: zod_1.z.string().min(6),
    roles: zod_1.z.array(zod_1.z.string()).default(['user']),
    permissions: zod_1.z.array(zod_1.z.string()).default([]),
    tenant_id: zod_1.z.string().default('default'),
});
router.post('/login', async (req, res) => {
    try {
        const body = LoginSchema.parse(req.body);
        const result = await gateway_1.apiGateway.authenticateUser(body.username, body.password);
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
    }
    catch (error) {
        logging_1.logger.error('Login failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/users', (req, res) => {
    try {
        const auth = req.auth;
        if (!auth) {
            res.status(401).json({ code: 401, error: 'Unauthorized' });
            return;
        }
        const body = CreateUserSchema.parse(req.body);
        const user = gateway_1.apiGateway.createUser(body.username, body.password, body.roles, body.permissions, body.tenant_id);
        if (!user) {
            res.status(409).json({ code: 409, error: 'Username already exists' });
            return;
        }
        logging_1.logger.info('User created via API', { username: body.username, created_by: auth.user_id });
        res.status(201).json({ code: 201, data: user });
    }
    catch (error) {
        logging_1.logger.error('User creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/api-keys', (req, res) => {
    try {
        const auth = req.auth;
        if (!auth) {
            res.status(401).json({ code: 401, error: 'Unauthorized' });
            return;
        }
        const { name, scopes, expires_at } = req.body;
        const expiresAt = expires_at ? new Date(expires_at) : undefined;
        const apiKey = gateway_1.apiGateway.createApiKey(auth.user_id, name || 'default', scopes || ['*'], expiresAt);
        res.status(201).json({ code: 201, data: apiKey });
    }
    catch (error) {
        logging_1.logger.error('API key creation failed', { error: error.message });
        res.status(400).json({ code: 400, error: error.message });
    }
});
router.post('/logout', (req, res) => {
    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
        const token = authHeader.slice(7);
        gateway_1.apiGateway.invalidateToken(token);
    }
    res.json({ code: 200, message: 'Logged out successfully' });
});
exports.default = router;
//# sourceMappingURL=auth.js.map