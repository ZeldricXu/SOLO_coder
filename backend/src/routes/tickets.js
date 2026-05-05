const express = require('express');
const router = express.Router();
const eventController = require('../controllers/eventController');
const ticketController = require('../controllers/ticketController');
const formFieldController = require('../controllers/formFieldController');
const registrationController = require('../controllers/registrationController');
const analyticsController = require('../controllers/analyticsController');
const checkInController = require('../controllers/checkInController');
const authMiddleware = require('../middleware/auth');

router.get('/', eventController.getEventList);
router.post('/', eventController.createEvent);

router.get('/:eventId', eventController.getEventById);
router.get('/:eventId/details', eventController.getEventWithDetails);
router.put('/:eventId', eventController.updateEvent);
router.delete('/:eventId', eventController.deleteEvent);

router.post('/:eventId/publish', eventController.publishEvent);
router.post('/:eventId/close', eventController.closeEvent);
router.post('/:eventId/cancel', eventController.cancelEvent);

router.get('/:eventId/tickets', ticketController.getTicketsByEvent);
router.post('/:eventId/tickets', ticketController.createTicket);
router.put('/:eventId/tickets/:ticketId', ticketController.updateTicket);
router.delete('/:eventId/tickets/:ticketId', ticketController.deleteTicket);

router.get('/:eventId/form-fields', formFieldController.getFormFieldsByEvent);
router.post('/:eventId/form-fields', formFieldController.createFormField);
router.put('/:eventId/form-fields/:fieldId', formFieldController.updateFormField);
router.delete('/:eventId/form-fields/:fieldId', formFieldController.deleteFormField);
router.post('/:eventId/form-fields/reorder', formFieldController.reorderFormFields);

router.get('/:eventId/registrations', registrationController.getRegistrationsByEvent);
router.get('/:eventId/registrations/stats', registrationController.getRegistrationStats);
router.get('/:eventId/registrations/full', registrationController.getEventWithRegistrations);

router.get('/:eventId/analytics/overview', analyticsController.getEventOverview);
router.get('/:eventId/analytics/registrations', analyticsController.getRegistrationTrend);
router.get('/:eventId/analytics/tickets', analyticsController.getTicketSales);
router.get('/:eventId/analytics/checkin', analyticsController.getCheckInStats);

router.get('/:eventId/checkins', checkInController.getCheckInsByEvent);
router.post('/:eventId/checkins/batch', checkInController.batchCheckIn);

module.exports = router;
