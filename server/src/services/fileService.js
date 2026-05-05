const db = require('../config/database');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
require('dotenv').config();

const UPLOAD_DIR = path.join(__dirname, '../../uploads');
const MAX_FILE_SIZE = parseInt(process.env.UPLOAD_MAX_SIZE) || 5 * 1024 * 1024;
const ALLOWED_TYPES = (process.env.UPLOAD_ALLOWED_TYPES || 'image/jpeg,image/png,image/gif,application/pdf').split(',');

const ensureUploadDir = () => {
  if (!fs.existsSync(UPLOAD_DIR)) {
    fs.mkdirSync(UPLOAD_DIR, { recursive: true });
  }
};

const validateFile = (file) => {
  if (!file) {
    throw new Error('未选择文件');
  }

  if (file.size > MAX_FILE_SIZE) {
    throw new Error(`文件大小不能超过 ${MAX_FILE_SIZE / 1024 / 1024}MB`);
  }

  if (!ALLOWED_TYPES.includes(file.mimetype)) {
    throw new Error('不支持的文件类型');
  }
};

const uploadAttachment = async (file, taskId, uploader) => {
  ensureUploadDir();
  validateFile(file);

  const [tasks] = await db.execute(
    'SELECT task_id FROM tasks WHERE task_id = ?',
    [taskId]
  );

  if (tasks.length === 0) {
    throw new Error('任务不存在');
  }

  const attachmentId = uuidv4();
  const fileExtension = path.extname(file.originalname);
  const fileName = `${attachmentId}${fileExtension}`;
  const filePath = path.join(UPLOAD_DIR, fileName);

  const tempPath = file.path || file.buffer;
  if (file.path) {
    fs.renameSync(file.path, filePath);
  } else if (file.buffer) {
    fs.writeFileSync(filePath, file.buffer);
  } else {
    throw new Error('文件数据无效');
  }

  await db.execute(
    `INSERT INTO attachments 
     (attachment_id, task_id, file_name, original_name, file_path, file_size, file_type, uploaded_by) 
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    [attachmentId, taskId, fileName, file.originalname, filePath, file.size, file.mimetype, uploader.user_id]
  );

  return {
    attachment_id: attachmentId,
    task_id: taskId,
    original_name: file.originalname,
    file_size: file.size,
    file_type: file.mimetype,
    uploaded_by: uploader.user_id,
    uploaded_at: new Date().toISOString()
  };
};

const getAttachmentsByTask = async (taskId) => {
  const [attachments] = await db.execute(
    `SELECT 
      a.*,
      u.username as uploaded_by_name
     FROM attachments a
     LEFT JOIN users u ON a.uploaded_by = u.user_id
     WHERE a.task_id = ?
     ORDER BY a.uploaded_at DESC`,
    [taskId]
  );
  return attachments;
};

const getAttachmentById = async (attachmentId) => {
  const [attachments] = await db.execute(
    `SELECT 
      a.*,
      u.username as uploaded_by_name
     FROM attachments a
     LEFT JOIN users u ON a.uploaded_by = u.user_id
     WHERE a.attachment_id = ?`,
    [attachmentId]
  );

  if (attachments.length === 0) {
    return null;
  }

  return attachments[0];
};

const downloadAttachment = async (attachmentId) => {
  const attachment = await getAttachmentById(attachmentId);
  if (!attachment) {
    throw new Error('附件不存在');
  }

  if (!fs.existsSync(attachment.file_path)) {
    throw new Error('文件已被删除');
  }

  return {
    file_path: attachment.file_path,
    original_name: attachment.original_name,
    file_type: attachment.file_type
  };
};

const deleteAttachment = async (attachmentId, userId) => {
  const attachment = await getAttachmentById(attachmentId);
  if (!attachment) {
    throw new Error('附件不存在');
  }

  if (attachment.uploaded_by !== userId) {
    throw new Error('无权删除此附件');
  }

  const connection = await db.getConnection();
  try {
    await connection.beginTransaction();

    await connection.execute(
      'DELETE FROM attachments WHERE attachment_id = ?',
      [attachmentId]
    );

    if (fs.existsSync(attachment.file_path)) {
      fs.unlinkSync(attachment.file_path);
    }

    await connection.commit();
    return true;
  } catch (error) {
    await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
};

module.exports = {
  uploadAttachment,
  getAttachmentsByTask,
  getAttachmentById,
  downloadAttachment,
  deleteAttachment,
  UPLOAD_DIR
};
