import express from 'express';
import { ReportGenerator } from '../services/ReportGenerator.js';
import { HTMLReportGenerator } from '../services/HTMLReportGenerator.js';
import { ParameterSweepService } from '../services/ParameterSweepService.js';
import { pack } from 'msgpackr';
export function setupApiRoutes(app, scheduler) {
    const router = express.Router();
    const reportGenerator = new ReportGenerator();
    const htmlReportGenerator = new HTMLReportGenerator();
    const sweepService = new ParameterSweepService(scheduler);
    router.post('/simulate', async (req, res) => {
        try {
            const { scene, config, duration } = req.body;
            if (!scene || !config) {
                return res.status(400).json({ error: 'Missing scene or config' });
            }
            const complexity = scheduler.estimateComplexity(scene, config);
            if (complexity.shouldOffload) {
                const jobId = await scheduler.scheduleSimulation(scene, config, duration);
                return res.json({
                    jobId,
                    complexity,
                    status: 'queued',
                    message: `Simulation queued for offloading (complexity: ${complexity.score.toFixed(2)})`,
                });
            }
            else {
                return res.json({
                    complexity,
                    status: 'local',
                    message: `Simulation can run locally (complexity: ${complexity.score.toFixed(2)})`,
                });
            }
        }
        catch (error) {
            console.error('Simulation error:', error);
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/jobs/:jobId', async (req, res) => {
        try {
            const { jobId } = req.params;
            const status = scheduler.getJobStatus(jobId);
            if (!status) {
                return res.status(404).json({ error: 'Job not found' });
            }
            return res.json(status);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/jobs/:jobId/result', async (req, res) => {
        try {
            const { jobId } = req.params;
            const result = await scheduler.getJobResult(jobId);
            if (!result) {
                return res.status(404).json({ error: 'Job not found or not completed' });
            }
            const acceptHeader = req.headers.accept;
            if (acceptHeader === 'application/msgpack') {
                res.setHeader('Content-Type', 'application/msgpack');
                const packed = pack(result);
                return res.send(Buffer.from(packed));
            }
            return res.json(result);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.delete('/jobs/:jobId', async (req, res) => {
        try {
            const { jobId } = req.params;
            const cancelled = scheduler.cancelJob(jobId);
            if (!cancelled) {
                return res.status(404).json({ error: 'Job not found' });
            }
            return res.json({ status: 'cancelled', jobId });
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/jobs', async (req, res) => {
        try {
            const jobs = scheduler.getAllJobs();
            return res.json(jobs);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.post('/sweep', async (req, res) => {
        try {
            const config = req.body;
            if (!config.parameterName || !config.baseConfig || !config.scene) {
                return res.status(400).json({ error: 'Missing parameterName, baseConfig, or scene' });
            }
            const sweepId = await sweepService.startSweep(config);
            return res.json({
                sweepId,
                status: 'started',
                message: `Parameter sweep started for ${config.parameterName}`,
            });
        }
        catch (error) {
            console.error('Sweep error:', error);
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/sweep/:sweepId', async (req, res) => {
        try {
            const { sweepId } = req.params;
            const status = sweepService.getSweepStatus(sweepId);
            if (!status) {
                return res.status(404).json({ error: 'Sweep not found' });
            }
            return res.json(status);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.delete('/sweep/:sweepId', async (req, res) => {
        try {
            const { sweepId } = req.params;
            const cancelled = sweepService.cancelSweep(sweepId);
            if (!cancelled) {
                return res.status(404).json({ error: 'Sweep not found' });
            }
            return res.json({ status: 'cancelled', sweepId });
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.post('/report/generate', async (req, res) => {
        try {
            const { scene, config, result, title, author, description, experimentPurpose, conclusion } = req.body;
            if (!scene || !config || !result) {
                return res.status(400).json({ error: 'Missing scene, config, or result' });
            }
            const reportData = {
                scene,
                config,
                result,
                title,
                author,
                description,
                experimentPurpose,
                conclusion,
            };
            const { latex, pdfPath } = await reportGenerator.generateFullReport(reportData);
            return res.json({
                status: 'completed',
                reportId: Date.now().toString(),
                latex,
                pdfPath,
            });
        }
        catch (error) {
            console.error('Report generation error:', error);
            return res.status(500).json({ error: error.message });
        }
    });
    router.post('/report/latex', async (req, res) => {
        try {
            const { scene, config, result, title, author, description, experimentPurpose, conclusion } = req.body;
            if (!scene || !config || !result) {
                return res.status(400).json({ error: 'Missing scene, config, or result' });
            }
            const reportData = {
                scene,
                config,
                result,
                title,
                author,
                description,
                experimentPurpose,
                conclusion,
            };
            const latex = await reportGenerator.generateLaTeX(reportData);
            res.setHeader('Content-Type', 'text/plain; charset=utf-8');
            res.setHeader('Content-Disposition', 'attachment; filename=report.tex');
            return res.send(latex);
        }
        catch (error) {
            console.error('LaTeX generation error:', error);
            return res.status(500).json({ error: error.message });
        }
    });
    router.post('/report/html', async (req, res) => {
        try {
            const { scene, config, result, title, author, description, experimentPurpose, conclusion, charts } = req.body;
            if (!scene || !config || !result) {
                return res.status(400).json({ error: 'Missing scene, config, or result' });
            }
            const html = await htmlReportGenerator.generateHTML({
                scene,
                config,
                result,
                title,
                author,
                description,
                experimentPurpose,
                conclusion,
                charts,
            });
            res.setHeader('Content-Type', 'text/html; charset=utf-8');
            res.setHeader('Content-Disposition', 'attachment; filename=report.html');
            return res.send(html);
        }
        catch (error) {
            console.error('HTML report generation error:', error);
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/templates', async (req, res) => {
        try {
            return res.json({
                templates: [
                    {
                        id: 'pendulum',
                        name: '单摆实验',
                        description: '研究简谐运动的周期与摆长的关系',
                        category: 'mechanics',
                        difficulty: 'beginner',
                    },
                    {
                        id: 'free-fall',
                        name: '自由落体',
                        description: '测量重力加速度',
                        category: 'mechanics',
                        difficulty: 'beginner',
                    },
                    {
                        id: 'coulomb',
                        name: '库仑定律',
                        description: '研究点电荷之间的相互作用力',
                        category: 'electromagnetics',
                        difficulty: 'intermediate',
                    },
                    {
                        id: 'cylinder-flow',
                        name: '圆柱绕流',
                        description: '观察流体绕圆柱的涡街现象',
                        category: 'fluiddynamics',
                        difficulty: 'intermediate',
                    },
                    {
                        id: 'heat-conduction',
                        name: '热传导',
                        description: '一维稳态热传导温度分布',
                        category: 'thermodynamics',
                        difficulty: 'beginner',
                    },
                ],
            });
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/templates/:id', async (req, res) => {
        try {
            const { id } = req.params;
            const templates = {
                pendulum: {
                    id: 'pendulum',
                    name: '单摆实验',
                    description: '研究简谐运动的周期与摆长的关系',
                    category: 'mechanics',
                    difficulty: 'beginner',
                    scene: {
                        id: 'template-pendulum',
                        name: '单摆实验',
                        objects: [],
                        sensors: [],
                        gravity: { x: 0, y: -9.8, z: 0 },
                    },
                },
                'cylinder-flow': {
                    id: 'cylinder-flow',
                    name: '圆柱绕流',
                    description: '观察流体绕圆柱的涡街现象',
                    category: 'fluiddynamics',
                    difficulty: 'intermediate',
                    scene: {
                        id: 'template-cylinder-flow',
                        name: '圆柱绕流',
                        objects: [],
                        sensors: [],
                        gravity: { x: 0, y: 0, z: 0 },
                        fluidConfig: {
                            width: 200,
                            height: 80,
                            viscosity: 0.02,
                            inletVelocity: { x: 0.1, y: 0, z: 0 },
                            obstacles: [
                                {
                                    id: 'cylinder',
                                    vertices: [],
                                    center: { x: 50, y: 40, z: 0 },
                                    radius: 10,
                                },
                            ],
                        },
                    },
                },
            };
            const template = templates[id];
            if (!template) {
                return res.status(404).json({ error: 'Template not found' });
            }
            return res.json(template);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/gpu/info', async (req, res) => {
        try {
            const gpuInfo = await scheduler.getGPUInfo();
            return res.json(gpuInfo);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    router.get('/stats', async (req, res) => {
        try {
            const stats = scheduler.getStats();
            return res.json(stats);
        }
        catch (error) {
            return res.status(500).json({ error: error.message });
        }
    });
    app.use('/api', router);
}
//# sourceMappingURL=api.js.map