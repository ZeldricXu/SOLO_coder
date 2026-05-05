const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const redisNotificationQueue = require('./redisNotificationQueue');

const createEvent = async (eventData, creator) => {
  const { title, description, start_time, end_time, participants, related_task_id, location } = eventData;
  const eventId = uuidv4();
  const now = new Date();

  const connection = await db.getConnection();
  try {
    await connection.beginTransaction();

    if (related_task_id) {
      const [tasks] = await connection.execute(
        'SELECT task_id FROM tasks WHERE task_id = ?',
        [related_task_id]
      );
      if (tasks.length === 0) {
        throw new Error('关联的任务不存在');
      }
    }

    await connection.execute(
      `INSERT INTO events 
       (event_id, title, description, start_time, end_time, related_task_id, location, created_by) 
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      [eventId, title, description || null, start_time, end_time, related_task_id || null, location || null, creator.user_id]
    );

    const validParticipants = [];
    const participantDetails = [];
    if (participants && participants.length > 0) {
      for (const participantId of participants) {
        const [users] = await connection.execute(
          'SELECT user_id, email, username FROM users WHERE user_id = ?',
          [participantId]
        );
        if (users.length > 0) {
          const participantIdGen = uuidv4();
          await connection.execute(
            `INSERT INTO event_participants (participant_id, event_id, user_id, status) 
             VALUES (?, ?, ?, ?)`,
            [participantIdGen, eventId, participantId, 'invited']
          );
          validParticipants.push(participantId);
          participantDetails.push(users[0]);
        }
      }
    }

    await connection.commit();

    const createdEvent = {
      event_id: eventId,
      title,
      description,
      start_time,
      end_time,
      participants: validParticipants,
      related_task_id: related_task_id || null,
      location,
      created_by: creator.user_id,
      created_at: now.toISOString(),
      updated_at: now.toISOString()
    };

    if (participantDetails.length > 0) {
      await redisNotificationQueue.enqueueNotification('event_created', {
        event: createdEvent,
        creator: creator,
        participants: participantDetails
      }, 8);
    }

    return {
      event_id: eventId,
      notifications_queued: participantDetails.length,
      event: createdEvent
    };
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
};

const getEvents = async (startDate, endDate, userId = null) => {
  let query = `
    SELECT 
      e.*,
      GROUP_CONCAT(DISTINCT ep.user_id) as participant_ids,
      GROUP_CONCAT(DISTINCT u.username) as participant_names,
      GROUP_CONCAT(DISTINCT ep.status) as participant_statuses
    FROM events e
    LEFT JOIN event_participants ep ON e.event_id = ep.event_id
    LEFT JOIN users u ON ep.user_id = u.user_id
    WHERE 1=1
  `;
  const params = [];

  if (startDate) {
    query += ' AND e.start_time <= ?';
    params.push(endDate || startDate);
  }

  if (endDate) {
    query += ' AND e.end_time >= ?';
    params.push(startDate || endDate);
  }

  if (userId) {
    query += ' AND (e.created_by = ? OR ep.user_id = ?)';
    params.push(userId, userId);
  }

  query += ' GROUP BY e.event_id ORDER BY e.start_time';

  const [events] = await db.execute(query, params);

  return events.map(event => {
    const participantIds = event.participant_ids ? event.participant_ids.split(',') : [];
    const participantNames = event.participant_names ? event.participant_names.split(',') : [];
    const participantStatuses = event.participant_statuses ? event.participant_statuses.split(',') : [];

    return {
      ...event,
      participants: participantIds.map((id, index) => ({
        user_id: id,
        username: participantNames[index] || null,
        status: participantStatuses[index] || 'invited'
      }))
    };
  });
};

const getEventById = async (eventId) => {
  const [events] = await db.execute(
    `SELECT 
      e.*,
      GROUP_CONCAT(DISTINCT ep.user_id) as participant_ids,
      GROUP_CONCAT(DISTINCT u.username) as participant_names,
      GROUP_CONCAT(DISTINCT ep.status) as participant_statuses
    FROM events e
    LEFT JOIN event_participants ep ON e.event_id = ep.event_id
    LEFT JOIN users u ON ep.user_id = u.user_id
    WHERE e.event_id = ?
    GROUP BY e.event_id`,
    [eventId]
  );

  if (events.length === 0) {
    return null;
  }

  const event = events[0];
  const participantIds = event.participant_ids ? event.participant_ids.split(',') : [];
  const participantNames = event.participant_names ? event.participant_names.split(',') : [];
  const participantStatuses = event.participant_statuses ? event.participant_statuses.split(',') : [];

  return {
    ...event,
    participants: participantIds.map((id, index) => ({
      user_id: id,
      username: participantNames[index] || null,
      status: participantStatuses[index] || 'invited'
    }))
  };
};

const updateEventParticipantStatus = async (eventId, userId, status) => {
  const validStatuses = ['invited', 'accepted', 'declined', 'tentative'];
  if (!validStatuses.includes(status)) {
    throw new Error('无效的状态值');
  }

  const [result] = await db.execute(
    'UPDATE event_participants SET status = ? WHERE event_id = ? AND user_id = ?',
    [status, eventId, userId]
  );

  return result.affectedRows > 0;
};

module.exports = {
  createEvent,
  getEvents,
  getEventById,
  updateEventParticipantStatus
};
