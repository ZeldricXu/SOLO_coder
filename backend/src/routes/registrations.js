const express = require('express');
const router = express.Router();
const registrationController = require('../controllers/registrationController');
const authMiddleware = require('../middleware/auth');

router.post('/', registrationController.submitRegistration);
router.get('/pending', registrationController.getPendingReviews);
router.get('/:registrationId', registrationController.getRegistrationById);
router.post('/:registrationId/review', registrationController.processReview);
router.post('/:registrationId/cancel', registrationController.cancelRegistration);

module.exports = router;
