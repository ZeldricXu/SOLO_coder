import express, { Application } from 'express';
import { setupApiRoutes } from '../routes/api';
import { SimulationScheduler } from '../services/SimulationScheduler';
import { ReportGenerator } from '../services/ReportGenerator';
import request from 'supertest';

describe('Backend API', () => {
  let app: Application;
  let scheduler: SimulationScheduler;

  beforeAll(async () => {
    app = express();
    app.use(express.json());
    scheduler = new SimulationScheduler();
    await scheduler.waitForInitialization();
    setupApiRoutes(app, scheduler);
  });

  afterAll(async () => {
    await scheduler.shutdown();
  });

  describe('POST /api/simulate', () => {
    it('should return 400 if scene is missing', async () => {
      const res = await request(app)
        .post('/api/simulate')
        .send({ config: { duration: 10, timeStep: 0.01, physicsTypes: ['mechanics'] } });
      expect(res.status).toBe(400);
      expect(res.body.error).toBeDefined();
    });

    it('should return complexity estimate for valid scene', async () => {
      const res = await request(app)
        .post('/api/simulate')
        .send({
          scene: {
            id: 'test-scene',
            name: 'Test',
            objects: [],
            sensors: [],
            gravity: { x: 0, y: -9.81, z: 0 },
          },
          config: {
            duration: 10,
            timeStep: 0.01,
            physicsTypes: ['mechanics'],
          },
        });
      expect(res.status).toBe(200);
      expect(res.body.complexity).toBeDefined();
      expect(res.body.complexity.score).toBeDefined();
    });
  });

  describe('GET /api/jobs', () => {
    it('should return empty jobs list initially', async () => {
      const res = await request(app).get('/api/jobs');
      expect(res.status).toBe(200);
      expect(Array.isArray(res.body)).toBe(true);
    });
  });

  describe('GET /api/jobs/:jobId', () => {
    it('should return 404 for non-existent job', async () => {
      const res = await request(app).get('/api/jobs/nonexistent-id');
      expect(res.status).toBe(404);
    });
  });

  describe('DELETE /api/jobs/:jobId', () => {
    it('should return 404 for non-existent job', async () => {
      const res = await request(app).delete('/api/jobs/nonexistent-id');
      expect(res.status).toBe(404);
    });
  });

  describe('GET /api/templates', () => {
    it('should return list of experiment templates', async () => {
      const res = await request(app).get('/api/templates');
      expect(res.status).toBe(200);
      expect(res.body.templates).toBeDefined();
      expect(Array.isArray(res.body.templates)).toBe(true);
      expect(res.body.templates.length).toBeGreaterThan(0);
    });
  });

  describe('GET /api/templates/:id', () => {
    it('should return specific template for valid id', async () => {
      const res = await request(app).get('/api/templates/pendulum');
      expect(res.status).toBe(200);
      expect(res.body.id).toBe('pendulum');
      expect(res.body.name).toBeDefined();
    });

    it('should return 404 for unknown template', async () => {
      const res = await request(app).get('/api/templates/unknown');
      expect(res.status).toBe(404);
    });
  });

  describe('POST /api/report/generate', () => {
    it('should return 400 if required fields are missing', async () => {
      const res = await request(app)
        .post('/api/report/generate')
        .send({ scene: {} });
      expect(res.status).toBe(400);
    });
  });

  describe('POST /api/report/latex', () => {
    it('should return 400 if required fields are missing', async () => {
      const res = await request(app)
        .post('/api/report/latex')
        .send({});
      expect(res.status).toBe(400);
    });
  });

  describe('POST /api/report/html', () => {
    it('should return 400 if required fields are missing', async () => {
      const res = await request(app)
        .post('/api/report/html')
        .send({});
      expect(res.status).toBe(400);
    });
  });

  describe('POST /api/sweep', () => {
    it('should return 400 if required fields are missing', async () => {
      const res = await request(app)
        .post('/api/sweep')
        .send({ parameterName: 'angle' });
      expect(res.status).toBe(400);
    });
  });

  describe('GET /api/sweep/:sweepId', () => {
    it('should return 404 for non-existent sweep', async () => {
      const res = await request(app).get('/api/sweep/nonexistent-id');
      expect(res.status).toBe(404);
    });
  });

  describe('DELETE /api/sweep/:sweepId', () => {
    it('should return 404 for non-existent sweep', async () => {
      const res = await request(app).delete('/api/sweep/nonexistent-id');
      expect(res.status).toBe(404);
    });
  });
});
