const express = require('express');
const router = express.Router();
const reportConfigController = require('../controllers/reportConfigController');
const authMiddleware = require('../middleware/auth');

router.get('/templates', reportConfigController.getTemplates);
router.get('/templates/:templateId', reportConfigController.getTemplateById);
router.get('/dimensions', reportConfigController.getAvailableDimensions);
router.get('/metrics', reportConfigController.getAvailableMetrics);
router.get('/chart-types', reportConfigController.getAvailableChartTypes);
router.get('/', reportConfigController.getConfigs);
router.post('/', reportConfigController.createConfig);
router.get('/:configId', reportConfigController.getConfigById);
router.put('/:configId', reportConfigController.updateConfig);
router.delete('/:configId', reportConfigController.deleteConfig);
router.post('/:configId/generate', reportConfigController.generateReportFromConfig);
router.post('/custom', reportConfigController.generateCustomReport);

module.exports = router;
