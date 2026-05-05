const express = require('express');
const router = express.Router();
const checkInController = require('../controllers/checkInController');
const authMiddleware = require('../middleware/auth');

router.post('/', checkInController.checkIn);
router.get('/event/:eventId', checkInController.getCheckInsByEvent);
router.post('/batch', checkInController.batchCheckIn);
router.get('/stats/:eventId', checkInController.getCheckInStats);

module.exports = router;
