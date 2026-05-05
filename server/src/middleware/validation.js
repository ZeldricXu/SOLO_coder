const Joi = require('joi');
const statusTransitionEngine = require('../services/statusTransitionEngine');

const getValidStatusValues = () => {
  const statusList = statusTransitionEngine.getAvailableStatusList();
  return statusList.map(s => s.value);
};

const validateTaskCreate = (req, res, next) => {
  const schema = Joi.object({
    title: Joi.string().required().max(255).messages({
      'any.required': '任务标题不能为空',
      'string.max': '任务标题不能超过255个字符'
    }),
    description: Joi.string().allow('').allow(null),
    assignees: Joi.array().items(Joi.string()).allow(null),
    due_date: Joi.date().iso().required().messages({
      'any.required': '截止日期不能为空',
      'date.iso': '截止日期格式不正确'
    }),
    priority: Joi.string().valid('low', 'medium', 'high', 'urgent').default('medium'),
    parent_task_id: Joi.string().allow(null)
  });

  const { error, value } = schema.validate(req.body);
  if (error) {
    return res.status(400).json({
      code: 400,
      message: error.details[0].message
    });
  }

  const dueDate = new Date(value.due_date);
  const now = new Date();
  now.setHours(0, 0, 0, 0);
  
  if (dueDate < now) {
    return res.status(400).json({
      code: 400,
      message: '截止日期不能早于当前日期'
    });
  }

  next();
};

const validateTaskStatusUpdate = (req, res, next) => {
  const validStatusValues = getValidStatusValues();
  
  const schema = Joi.object({
    task_id: Joi.string().required().messages({
      'any.required': '任务ID不能为空'
    }),
    new_status: Joi.string().valid(...validStatusValues).required().messages({
      'any.required': '新状态不能为空',
      'any.only': `状态值无效，可选值：${validStatusValues.join(', ')}`
    }),
    progress: Joi.number().integer().min(0).max(100).allow(null),
    version: Joi.number().integer().min(1).allow(null)
  });

  const { error } = schema.validate(req.body);
  if (error) {
    return res.status(400).json({
      code: 400,
      message: error.details[0].message
    });
  }

  next();
};

const validateEventCreate = (req, res, next) => {
  const schema = Joi.object({
    title: Joi.string().required().max(255).messages({
      'any.required': '日程标题不能为空',
      'string.max': '日程标题不能超过255个字符'
    }),
    description: Joi.string().allow('').allow(null),
    start_time: Joi.date().iso().required().messages({
      'any.required': '开始时间不能为空',
      'date.iso': '开始时间格式不正确'
    }),
    end_time: Joi.date().iso().required().messages({
      'any.required': '结束时间不能为空',
      'date.iso': '结束时间格式不正确'
    }),
    participants: Joi.array().items(Joi.string()).allow(null),
    related_task_id: Joi.string().allow(null),
    location: Joi.string().allow('').allow(null)
  });

  const { error, value } = schema.validate(req.body);
  if (error) {
    return res.status(400).json({
      code: 400,
      message: error.details[0].message
    });
  }

  const startTime = new Date(value.start_time);
  const endTime = new Date(value.end_time);
  
  if (endTime <= startTime) {
    return res.status(400).json({
      code: 400,
      message: '结束时间必须晚于开始时间'
    });
  }

  next();
};

const validateEventQuery = (req, res, next) => {
  const schema = Joi.object({
    start_date: Joi.date().iso().required().messages({
      'any.required': '开始日期不能为空',
      'date.iso': '开始日期格式不正确'
    }),
    end_date: Joi.date().iso().required().messages({
      'any.required': '结束日期不能为空',
      'date.iso': '结束日期格式不正确'
    })
  });

  const { error, value } = schema.validate(req.query);
  if (error) {
    return res.status(400).json({
      code: 400,
      message: error.details[0].message
    });
  }

  const startDate = new Date(value.start_date);
  const endDate = new Date(value.end_date);
  
  if (endDate < startDate) {
    return res.status(400).json({
      code: 400,
      message: '结束日期不能早于开始日期'
    });
  }

  next();
};

module.exports = {
  validateTaskCreate,
  validateTaskStatusUpdate,
  validateEventCreate,
  validateEventQuery,
  getValidStatusValues
};
