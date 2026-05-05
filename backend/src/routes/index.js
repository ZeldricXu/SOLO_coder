const express = require('express');
const router = express.Router();

const eventsRouter = require('./events');
const registrationsRouter = require('./registrations');
const checkinsRouter = require('./checkins');
const ticketsRouter = require('./tickets');
const formFieldsRouter = require('./formFields');
const analyticsRouter = require('./analytics');
const queueRouter = require('./queue');
const reportsRouter = require('./reports');

router.use('/events', eventsRouter);
router.use('/registrations', registrationsRouter);
router.use('/checkins', checkinsRouter);
router.use('/tickets', ticketsRouter);
router.use('/form-fields', formFieldsRouter);
router.use('/analytics', analyticsRouter);
router.use('/queue', queueRouter);
router.use('/reports', reportsRouter);

module.exports = router;
