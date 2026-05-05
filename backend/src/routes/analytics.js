const express = require('express');
const router = express.Router();
const analyticsController = require('../controllers/analyticsController');
const authMiddleware = require('../middleware/auth');

router.get('/event/:eventId/overview', analyticsController.getEventOverview);
router.get('/event/:eventId/registrations', analyticsController.getRegistrationTrend);
router.get('/event/:eventId/tickets', analyticsController.getTicketSales);
router.get('/event/:eventId/checkin', analyticsController.getCheckInStats);
router.get('/event/:eventId/revenue', analyticsController.getRevenueStats);

router.get('/dimensions', analyticsController.getAvailableDimensions);
router.get('/metrics', analyticsController.getAvailableMetrics);
router.get('/chart-types', analyticsController.getAvailableChartTypes);
router.get('/templates', analyticsController.getReportTemplates);

router.post('/custom', analyticsController.getCustomReport);
router.post('/from-template', analyticsController.generateFromTemplate);

module.exports = router;
