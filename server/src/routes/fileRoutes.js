const express = require('express');
const router = express.Router();
const multer = require('multer');
const path = require('path');
const { authenticateToken } = require('../middleware/auth');
const fileService = require('../services/fileService');

const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    const tempDir = path.join(__dirname, '../../temp');
    const fs = require('fs');
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }
    cb(null, tempDir);
  },
  filename: (req, file, cb) => {
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1E9);
    cb(null, file.fieldname + '-' + uniqueSuffix + path.extname(file.originalname));
  }
});

const upload = multer({
  storage: storage,
  limits: {
    fileSize: parseInt(process.env.UPLOAD_MAX_SIZE) || 5 * 1024 * 1024
  }
});

router.get('/task/:taskId', authenticateToken, async (req, res) => {
  try {
    const { taskId } = req.params;
    const attachments = await fileService.getAttachmentsByTask(taskId);

    res.json({
      code: 200,
      data: {
        attachments
      }
    });
  } catch (error) {
    console.error('获取附件列表错误:', error);
    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.get('/download/:attachmentId', authenticateToken, async (req, res) => {
  try {
    const { attachmentId } = req.params;
    const fileInfo = await fileService.downloadAttachment(attachmentId);

    res.download(fileInfo.file_path, fileInfo.original_name, (err) => {
      if (err) {
        console.error('下载文件错误:', err);
        res.status(500).json({
          code: 500,
          message: '下载文件失败'
        });
      }
    });
  } catch (error) {
    console.error('下载附件错误:', error);

    if (error.message === '附件不存在' || error.message === '文件已被删除') {
      return res.status(404).json({
        code: 404,
        message: error.message
      });
    }

    res.status(500).json({
      code: 500,
      message: '服务器内部错误'
    });
  }
});

router.post('/upload', authenticateToken, upload.single('file'), async (req, res) => {
  try {
    const { task_id } = req.body;

    if (!task_id) {
      return res.status(400).json({
        code: 400,
        message: '任务ID不能为空'
      });
    }

    if (!req.file) {
      return res.status(400).json({
        code: 400,
        message: '未选择文件'
      });
    }

    const result = await fileService.uploadAttachment(req.file, task_id, req.user);

    res.status(201).json({
      code: 200,
      message: '上传成功',
      data: result
    });
  } catch (error) {
    console.error('上传附件错误:', error);

    if (error.message === '任务不存在' || 
        error.message === '未选择文件' ||
        error.message.includes('文件大小') ||
        error.message.includes('不支持的文件类型')) {
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

router.delete('/:attachmentId', authenticateToken, async (req, res) => {
  try {
    const { attachmentId } = req.params;
    const success = await fileService.deleteAttachment(attachmentId, req.user.user_id);

    if (!success) {
      return res.status(404).json({
        code: 404,
        message: '附件不存在'
      });
    }

    res.json({
      code: 200,
      message: '删除成功'
    });
  } catch (error) {
    console.error('删除附件错误:', error);

    if (error.message === '附件不存在') {
      return res.status(404).json({
        code: 404,
        message: error.message
      });
    }

    if (error.message === '无权删除此附件') {
      return res.status(403).json({
        code: 403,
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
