const eventService = require('../services/eventService');
const responseUtil = require('../utils/response');

const eventController = {
  async createEvent(req, res) {
    try {
      const { user } = req;
      const eventData = {
        ...req.body,
        organizer_id: user?.user_id || req.body.organizer_id
      };

      const event = await eventService.createEvent(eventData);
      responseUtil.success(res, event, 'Event created successfully');
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async getEventById(req, res) {
    try {
      const { eventId } = req.params;
      const event = await eventService.getEventById(eventId);
      responseUtil.success(res, event);
    } catch (error) {
      responseUtil.error(res, error.message, 404);
    }
  },

  async getEventWithDetails(req, res) {
    try {
      const { eventId } = req.params;
      const event = await eventService.getEventWithDetails(eventId);
      responseUtil.success(res, event);
    } catch (error) {
      responseUtil.error(res, error.message, 404);
    }
  },

  async getEventList(req, res) {
    try {
      const { user } = req;
      const { status } = req.query;
      
      const filters = {};
      if (status) {
        filters.status = status;
      }

      const events = await eventService.getEventList(user?.user_id, filters);
      responseUtil.success(res, events);
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async updateEvent(req, res) {
    try {
      const { eventId } = req.params;
      const { user } = req;
      const updateData = req.body;

      const event = await eventService.updateEvent(eventId, updateData, user?.user_id);
      responseUtil.success(res, event, 'Event updated successfully');
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async deleteEvent(req, res) {
    try {
      const { eventId } = req.params;
      const { user } = req;

      const success = await eventService.deleteEvent(eventId, user?.user_id);
      if (success) {
        responseUtil.success(res, null, 'Event deleted successfully');
      } else {
        responseUtil.error(res, 'Failed to delete event', 400);
      }
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async publishEvent(req, res) {
    try {
      const { eventId } = req.params;
      const { user } = req;

      const event = await eventService.publishEvent(eventId, user?.user_id);
      responseUtil.success(res, event, 'Event published successfully');
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async closeEvent(req, res) {
    try {
      const { eventId } = req.params;
      const { user } = req;

      const event = await eventService.closeEvent(eventId, user?.user_id);
      responseUtil.success(res, event, 'Event closed successfully');
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  },

  async cancelEvent(req, res) {
    try {
      const { eventId } = req.params;
      const { user } = req;

      const event = await eventService.cancelEvent(eventId, user?.user_id);
      responseUtil.success(res, event, 'Event cancelled successfully');
    } catch (error) {
      responseUtil.error(res, error.message, 400);
    }
  }
};

module.exports = eventController;
