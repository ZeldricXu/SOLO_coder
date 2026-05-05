const registrationService = require('../services/registrationService');
const eventService = require('../services/eventService');
const responseUtil = require('../utils/response');

const registrationController = {
  async submitRegistration(req, res) {
    try {
      const registrationData = req.body;
      const registration = await registrationService.submitRegistration(registrationData);
      
      responseUtil.success(res, {
        registration_id: registration.registration_id,
        status: registration.status
      }, 'Registration submitted successfully');
    } catch (error) {
      if (error.errors) {
        return res.status(400).json({
          code: 400,
          message: 'Form validation failed',
          errors: error.errors
        });
      }
      responseUtil.error(res, error.message, 400);
    }
  },

  async getRegistrationById(req, res) {
    try {
      const { registrationId } = req.params;
      const registration = await registrationService.getRegistrationById(registrationId);
      responseUtil.success(res, registration);
    } catch (error) {
      responseUtil.error(res, error.message, 404);
    }
  },

  async getRegistrationsByEvent(req, res) {
    try {
      const { eventId } = req.params;
      const { status, check_in_status } = req.query;
      
      const filters = {};
      if (status) {
        filters.status = status;
      }
      if (check_in_status !== undefined) {
        filters.check_in_status = check_in_status === 'true';
      }

      const registrations = await registrationService.getRegistrationsByEvent(eventId, filters);
      responseUtil.success(res, registrations);
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async processReview(req, res) {
    try {
      const { registrationId } = req.params;
      const { action, notes } = req.body;
      const { user } = req;

      if (!action || !['approve', 'reject'].includes(action)) {
        return responseUtil.error(res, 'Invalid action, must be "approve" or "reject"', 400);
      }

      const registration = await registrationService.processReview(
        registrationId,
        action,
        notes,
        user?.user_id
      );

      responseUtil.success(res, {
        status: registration.status,
        approved_at: registration.approved_at
      }, `Registration ${action}d successfully`);
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async getPendingReviews(req, res) {
    try {
      const { eventId } = req.query;
      
      const filters = { status: 'pending_review' };
      if (eventId) {
        filters.event_id = eventId;
      }

      const registrations = await registrationService.getRegistrationsByEvent(
        eventId,
        { status: 'pending_review' }
      );
      responseUtil.success(res, registrations);
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async cancelRegistration(req, res) {
    try {
      const { registrationId } = req.params;
      const registration = await registrationService.cancelRegistration(registrationId);
      responseUtil.success(res, registration, 'Registration cancelled successfully');
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async getRegistrationStats(req, res) {
    try {
      const { eventId } = req.params;
      const stats = await registrationService.getRegistrationStats(eventId);
      responseUtil.success(res, stats);
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async getEventWithRegistrations(req, res) {
    try {
      const { eventId } = req.params;
      const event = await eventService.getEventById(eventId);
      const registrations = await registrationService.getRegistrationsByEvent(eventId);
      const stats = await registrationService.getRegistrationStats(eventId);

      responseUtil.success(res, {
        event,
        registrations,
        stats
      });
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  }
};

module.exports = registrationController;
