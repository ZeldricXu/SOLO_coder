const express = require('express');
const router = express.Router();
const queueController = require('../controllers/queueController');
const authMiddleware = require('../middleware/auth');

router.get('/stats', queueController.getStats);
router.get('/pending', queueController.getPendingMessages);
router.post('/process', queueController.processQueueManually);
router.post('/email', queueController.enqueueEmail);
router.post('/sms', queueController.enqueueSMS);
router.post('/:queueId/cancel', queueController.cancelMessage);
router.post('/:queueId/retry', queueController.retryMessage);

module.exports = router;
