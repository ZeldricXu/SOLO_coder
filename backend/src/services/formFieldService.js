const eventModel = require('../models/eventModel');
const ticketModel = require('../models/ticketModel');
const formFieldModel = require('../models/formFieldModel');

const eventService = {
  async createEvent(eventData) {
    if (!eventData.title || !eventData.start_time || !eventData.end_time) {
      throw new Error('Missing required fields: title, start_time, end_time');
    }

    if (new Date(eventData.end_time) <= new Date(eventData.start_time)) {
      throw new Error('End time must be after start time');
    }

    return await eventModel.create(eventData);
  },

  async getEventById(eventId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new Error('Event not found');
    }
    return event;
  },

  async getEventList(organizerId, filters = {}) {
    const queryFilters = { ...filters };
    if (organizerId) {
      queryFilters.organizer_id = organizerId;
    }
    return await eventModel.findAll(queryFilters);
  },

  async updateEvent(eventId, updateData, organizerId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new Error('Event not found');
    }

    if (organizerId && event.organizer_id !== organizerId) {
      throw new Error('Permission denied');
    }

    if (updateData.start_time && updateData.end_time) {
      if (new Date(updateData.end_time) <= new Date(updateData.start_time)) {
        throw new Error('End time must be after start time');
      }
    }

    return await eventModel.update(eventId, updateData);
  },

  async deleteEvent(eventId, organizerId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new Error('Event not found');
    }

    if (organizerId && event.organizer_id !== organizerId) {
      throw new Error('Permission denied');
    }

    return await eventModel.delete(eventId);
  },

  async publishEvent(eventId, organizerId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new Error('Event not found');
    }

    if (organizerId && event.organizer_id !== organizerId) {
      throw new Error('Permission denied');
    }

    if (event.status !== 'draft') {
      throw new Error('Only draft events can be published');
    }

    return await eventModel.publish(eventId);
  },

  async closeEvent(eventId, organizerId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new Error('Event not found');
    }

    if (organizerId && event.organizer_id !== organizerId) {
      throw new Error('Permission denied');
    }

    if (event.status !== 'published') {
      throw new Error('Only published events can be closed');
    }

    return await eventModel.close(eventId);
  },

  async cancelEvent(eventId, organizerId) {
    const event = await eventModel.findById(eventId);
    if (!event) {
      throw new Error('Event not found');
    }

    if (organizerId && event.organizer_id !== organizerId) {
      throw new Error('Permission denied');
    }

    if (event.status === 'cancelled') {
      throw new Error('Event is already cancelled');
    }

    return await eventModel.cancel(eventId);
  },

  async getEventWithDetails(eventId) {
    const event = await this.getEventById(eventId);
    const tickets = await ticketModel.findByEventId(eventId);
    const formFields = await formFieldModel.findByEventId(eventId);

    return {
      ...event,
      tickets,
      form_fields: formFields
    };
  }
};

module.exports = eventService;
