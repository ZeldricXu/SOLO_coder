const express = require('express');
const router = express.Router();
const { authenticateToken } = require('../middleware/auth');
const notificationService = require('../services/notificationService');

router.get('/unread', authenticateToken, async (req, res) => {
  try {
    const notifications = await notificationService.getUnreadNotifications(req.user.user_id);

    res.json({
      code: 200,
      data: {
        notifications,
        count: notifications.length
      }
    });
  } catch (error) {
    console.error('获取未读通知错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.put('/:notificationId/read', authenticateToken, async (req, res) => {
  try {
    const { notificationId } = req.params;
    const success = await notificationService.markNotificationAsRead(notificationId, req.user.user_id);

    if (!success) {
      return res.status(404).json({
        code: 404,
        message: '通知不存在'
      });
    }

    res.json({
      code: 200,
      message: '已标记为已读'
    });
  } catch (error) {
    console.error('标记通知已读错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

module.exports = router;
