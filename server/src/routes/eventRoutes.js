const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middleware/auth');
const { validateEventCreate, validateEventQuery } = require('../middleware/validation');
const eventService = require('../services/eventService');

router.get('/', authenticateToken, validateEventQuery, async (req, res) => {
  try {
    const { start_date, end_date } = req.query;
    const events = await eventService.getEvents(start_date, end_date, req.user.user_id);

    res.json({
      code: 200,
      data: {
        events
      }
    });
  } catch (error) {
    console.error('获取日程列表错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.get('/:eventId', authenticateToken, async (req, res) => {
  try {
    const { eventId } = req.params;
    const event = await eventService.getEventById(eventId);

    if (!event) {
      return res.status(404).json({
        code: 404,
        message: '日程不存在'
      });
    }

    res.json({
      code: 200,
      data: event
    });
  } catch (error) {
    console.error('获取日程详情错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.post('/create', authenticateToken, validateEventCreate, async (req, res) => {
  try {
    const result = await eventService.createEvent(req.body, req.user);

    res.status(201).json({
      code: 200,
      data: {
        event_id: result.event_id,
        notifications_sent: result.notifications_sent,
        event: result.event
      }
    });
  } catch (error) {
    console.error('创建日程错误:', error);

    if (error.message === '关联的任务不存在') {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }

    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.put('/:eventId/participation', authenticateToken, async (req, res) => {
  try {
    const { eventId } = req.params;
    const { status } = req.body;

    if (!status) {
      return res.status(400).json({
        code: 400,
        message: '状态不能为空'
      });
    }

    const success = await eventService.updateEventParticipantStatus(eventId, req.user.user_id, status);

    if (!success) {
      return res.status(404).json({
        code: 404,
        message: '日程不存在或您不是该日程的参与者'
      });
    }

    res.json({
      code: 200,
      message: '参与状态已更新'
    });
  } catch (error) {
    console.error('更新日程参与状态错误:', error);

    if (error.message === '无效的状态值') {
      return res.status(400).json({
        code: 400,
        message: error.message
      });
    }

    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

module.exports = router;
