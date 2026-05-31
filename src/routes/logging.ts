import { Router, Request, Response } from 'express';
import { logger, LoggerService } from '../logging';

const router = Router();

router.post('/level', (req: Request, res: Response) => {
  const { level, module } = req.body;

  if (!level || !['debug', 'info', 'warn', 'error', 'fatal'].includes(level)) {
    res.status(400).json({ code: 400, error: 'Valid log level required: debug, info, warn, error, fatal' });
    return;
  }

  if (module) {
    logger.setModuleLevel(module, level);
    res.json({ code: 200, message: `Log level for module '${module}' set to ${level}` });
  } else {
    logger.setLevel(level);
    res.json({ code: 200, message: `Global log level set to ${level}` });
  }
});

router.get('/level', (req: Request, res: Response) => {
  const module = req.query.module as string;

  if (module) {
    const level = logger.getModuleLevel(module);
    res.json({ code: 200, data: { module, level } });
  } else {
    const level = logger.getLevel();
    res.json({ code: 200, data: { level } });
  }
});

router.get('/levels', (req: Request, res: Response) => {
  const levels = (logger as unknown as { moduleLevels?: Map<string, string> }).moduleLevels;
  const response: Record<string, string> = {};
  if (levels) {
    levels.forEach((v, k) => { response[k] = v; });
  }
  res.json({ code: 200, data: { global: logger.getLevel(), modules: response } });
});

router.post('/message', (req: Request, res: Response) => {
  const { level, message, data, module } = req.body;

  if (!level || !message) {
    res.status(400).json({ code: 400, error: 'level and message are required' });
    return;
  }

  const logFn = (logger as any)[level];
  if (typeof logFn !== 'function') {
    res.status(400).json({ code: 400, error: 'Invalid log level' });
    return;
  }

  logFn.call(logger, message, { ...data, module });
  res.json({ code: 200, message: 'Message logged' });
});

router.post('/flush', (req: Request, res: Response) => {
  const transportName = req.query.transport as string;
  (logger as any).flush?.(transportName);
  res.json({ code: 200, message: 'Logs flushed' });
});

router.get('/config', (req: Request, res: Response) => {
  res.json({
    code: 200,
    data: {
      level: logger.getLevel(),
      transports: ['console', 'file'],
      include_timestamp: true,
      format: 'json',
    }
  });
});

export default router;
