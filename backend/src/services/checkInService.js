const db = require('../config/database');
const eventModel = require('../models/eventModel');
const ticketModel = require('../models/ticketModel');
const formFieldModel = require('../models/formFieldModel');
const registrationModel = require('../models/registrationModel');
const notificationModel = require('../models/notificationModel');
const notificationService = require('./notificationService');

const registrationService = {
  async submitRegistration(registrationData) {
    const { event_id, ticket_id, form_data } = registrationData;

    const event = await eventModel.findById(event_id);
    if (!event) {
      throw new Error('Event not found');
    }

    if (event.status !== 'published') {
      throw new Error('Event is not open for registration');
    }

    let ticket = null;
    if (ticket_id) {
      ticket = await ticketModel.findById(ticket_id);
      if (!ticket) {
        throw new Error('Ticket type not found');
      }
      if (ticket.event_id !== event_id) {
        throw new Error('Ticket does not belong to this event');
      }
      if (ticket.status !== 'available') {
        throw new Error('Ticket is not available');
      }

      const remainingQuota = ticket.quota - ticket.sold_count;
      if (remainingQuota <= 0) {
        throw new Error('Ticket quota is exhausted');
      }
    }

    const formFields = await formFieldModel.findByEventId(event_id);
    this.validateFormData(form_data, formFields);

    let registrationStatus = event.need_approval ? 'pending_review' : 'approved';

    let totalAmount = ticket ? ticket.price : 0;

    let updatedTicket = null;
    if (ticket && ticket.quota > 0) {
      updatedTicket = await this.decreaseTicketQuota(ticket_id);
    }

    const registration = await registrationModel.create({
      event_id,
      ticket_id: ticket ? ticket.ticket_id : null,
      ticket_name: ticket ? ticket.ticket_name : null,
      user_id: registrationData.user_id || null,
      form_data,
      status: registrationStatus,
      total_amount: totalAmount,
      notes: registrationData.notes
    });

    try {
      if (registrationStatus === 'pending_review') {
        await notificationService.createNotification({
          registration_id: registration.registration_id,
          event_id,
          notification_type: 'approval_pending',
          recipient: this.extractEmailFromFormData(form_data) || '',
          recipient_type: 'email',
          subject: '报名审核中',
          content: `您的活动报名已提交，正在等待审核中。活动：${event.title}`
        });
      } else {
        await notificationService.createNotification({
          registration_id: registration.registration_id,
          event_id,
          notification_type: 'approved',
          recipient: this.extractEmailFromFormData(form_data) || '',
          recipient_type: 'email',
          subject: '报名确认通知',
          content: `您的活动报名已确认！活动：${event.title}，时间：${event.start_time}`
        });
      }
    } catch (notifErr) {
      console.error('Failed to create notification:', notifErr);
    }

    return registration;
  },

  validateFormData(formData, formFields) {
    const errors = [];

    for (const field of formFields) {
      const fieldValue = formData[field.field_name];

      if (field.required && (fieldValue === undefined || fieldValue === null || fieldValue === '')) {
        errors.push({
          field: field.field_name,
          message: `${field.field_label} 为必填项`
        });
        continue;
      }

      if (fieldValue === undefined || fieldValue === null || fieldValue === '') {
        continue;
      }

      switch (field.field_type) {
        case 'email':
          if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(fieldValue)) {
            errors.push({
              field: field.field_name,
              message: `${field.field_label} 格式不正确`
            });
          }
          break;
        case 'phone':
          if (!/^1[3-9]\d{9}$/.test(fieldValue)) {
            errors.push({
              field: field.field_name,
              message: `${field.field_label} 格式不正确`
            });
          }
          break;
        case 'number':
          if (isNaN(Number(fieldValue))) {
            errors.push({
              field: field.field_name,
              message: `${field.field_label} 必须是数字`
            });
          }
          break;
        case 'select':
        case 'radio':
          if (field.options && field.options.length > 0) {
            const validOptions = field.options.map(opt => opt.value);
            if (!validOptions.includes(fieldValue)) {
              errors.push({
                field: field.field_name,
                message: `${field.field_label} 选项无效`
              });
            }
          }
          break;
        case 'checkbox':
          if (field.options && field.options.length > 0) {
            const validOptions = field.options.map(opt => opt.value);
            const values = Array.isArray(fieldValue) ? fieldValue : [fieldValue];
            for (const val of values) {
              if (!validOptions.includes(val)) {
                errors.push({
                  field: field.field_name,
                  message: `${field.field_label} 包含无效选项`
                });
                break;
              }
            }
          }
          break;
      }

      if (field.validation_rules) {
        const rules = typeof field.validation_rules === 'string' 
          ? JSON.parse(field.validation_rules) 
          : field.validation_rules;
        
        if (rules.minLength && fieldValue.length < rules.minLength) {
          errors.push({
            field: field.field_name,
            message: `${field.field_label} 最少需要 ${rules.minLength} 个字符`
          });
        }
        if (rules.maxLength && fieldValue.length > rules.maxLength) {
          errors.push({
            field: field.field_name,
            message: `${field.field_label} 最多允许 ${rules.maxLength} 个字符`
          });
        }
      }
    }

    if (errors.length > 0) {
      const validationError = new Error('Form validation failed');
      validationError.errors = errors;
      throw validationError;
    }
  },

  extractEmailFromFormData(formData) {
    for (const key in formData) {
      if (key.toLowerCase().includes('email') && 
          /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData[key])) {
        return formData[key];
      }
    }
    return null;
  },

  async decreaseTicketQuota(ticketId) {
    const maxRetries = 3;
    let retries = 0;

    while (retries < maxRetries) {
      const ticket = await ticketModel.findById(ticketId);
      if (!ticket) {
        throw new Error('Ticket not found');
      }

      const remainingQuota = ticket.quota - ticket.sold_count;
      if (remainingQuota <= 0) {
        throw new Error('Ticket quota is exhausted');
      }

      const newSoldCount = ticket.sold_count + 1;
      const newStatus = newSoldCount >= ticket.quota ? 'sold_out' : 'available';

      const success = await ticketModel.updateWithVersion(
        ticketId,
        { sold_count: newSoldCount, status: newStatus },
        ticket.version
      );

      if (success) {
        return await ticketModel.findById(ticketId);
      }

      retries++;
      if (retries >= maxRetries) {
        throw new Error('Failed to update ticket quota, please try again');
      }

      await new Promise(resolve => setTimeout(resolve, 100));
    }
  },

  async getRegistrationById(registrationId) {
    const registration = await registrationModel.findById(registrationId);
    if (!registration) {
      throw new Error('Registration not found');
    }
    return registration;
  },

  async getRegistrationsByEvent(eventId, filters = {}) {
    return await registrationModel.findByEventId(eventId, filters);
  },

  async processReview(registrationId, action, notes, organizerId) {
    const registration = await registrationModel.findById(registrationId);
    if (!registration) {
      throw new Error('Registration not found');
    }

    if (registration.status !== 'pending_review') {
      throw new Error('Registration is not pending review');
    }

    let updatedRegistration;

    if (action === 'approve') {
      updatedRegistration = await registrationModel.approve(registrationId, notes);

      try {
        const event = await eventModel.findById(registration.event_id);
        const email = this.extractEmailFromFormData(
          typeof registration.form_data === 'string' 
            ? JSON.parse(registration.form_data) 
            : registration.form_data
        );
        
        await notificationService.createNotification({
          registration_id: registrationId,
          event_id: registration.event_id,
          notification_type: 'approved',
          recipient: email || '',
          recipient_type: 'email',
          subject: '报名审核通过',
          content: `恭喜！您的活动报名已审核通过。活动：${event.title}，时间：${event.start_time}`
        });
      } catch (err) {
        console.error('Failed to send approval notification:', err);
      }
    } else if (action === 'reject') {
      updatedRegistration = await registrationModel.reject(registrationId, notes);

      if (registration.ticket_id) {
        await ticketModel.increaseQuota(registration.ticket_id);
      }

      try {
        const event = await eventModel.findById(registration.event_id);
        const email = this.extractEmailFromFormData(
          typeof registration.form_data === 'string' 
            ? JSON.parse(registration.form_data) 
            : registration.form_data
        );
        
        await notificationService.createNotification({
          registration_id: registrationId,
          event_id: registration.event_id,
          notification_type: 'rejected',
          recipient: email || '',
          recipient_type: 'email',
          subject: '报名审核结果',
          content: `您的活动报名未通过审核。活动：${event.title}${notes ? `，原因：${notes}` : ''}`
        });
      } catch (err) {
        console.error('Failed to send rejection notification:', err);
      }
    } else {
      throw new Error('Invalid action');
    }

    return updatedRegistration;
  },

  async cancelRegistration(registrationId) {
    const registration = await registrationModel.findById(registrationId);
    if (!registration) {
      throw new Error('Registration not found');
    }

    if (registration.status === 'cancelled') {
      throw new Error('Registration is already cancelled');
    }

    if (registration.ticket_id && registration.status === 'approved') {
      await ticketModel.increaseQuota(registration.ticket_id);
    }

    return await registrationModel.cancel(registrationId);
  },

  async getRegistrationStats(eventId) {
    const [totalCount, pendingCount, approvedCount, rejectedCount, checkInCount] = await Promise.all([
      registrationModel.countByEventId(eventId),
      registrationModel.countByEventId(eventId, { status: 'pending_review' }),
      registrationModel.countByEventId(eventId, { status: 'approved' }),
      registrationModel.countByEventId(eventId, { status: 'rejected' }),
      registrationModel.countByEventId(eventId, { check_in_status: true })
    ]);

    return {
      total: totalCount,
      pending_review: pendingCount,
      approved: approvedCount,
      rejected: rejectedCount,
      checked_in: checkInCount
    };
  }
};

module.exports = registrationService;
