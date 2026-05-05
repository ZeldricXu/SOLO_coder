export const VALIDATION_TYPE = {
  REQUIRED: 'required',
  MIN_LENGTH: 'min_length',
  MAX_LENGTH: 'max_length',
  PATTERN: 'pattern',
  MIN: 'min',
  MAX: 'max',
  EMAIL: 'email',
  PHONE: 'phone',
  ID_CARD: 'id_card',
  CUSTOM: 'custom',
};

export const PATTERNS = {
  [VALIDATION_TYPE.EMAIL]: /^[\w-]+(\.[\w-]+)*@[\w-]+(\.[\w-]+)+$/,
  [VALIDATION_TYPE.PHONE]: /^1[3-9]\d{9}$/,
  [VALIDATION_TYPE.ID_CARD]: /(^\d{15}$)|(^\d{18}$)|(^\d{17}(\d|X|x)$)/,
};

export const VALIDATION_MESSAGES = {
  [VALIDATION_TYPE.REQUIRED]: (label = '此字段') => `${label}不能为空`,
  [VALIDATION_TYPE.MIN_LENGTH]: (label = '此字段', min) => `${label}长度不能小于${min}个字符`,
  [VALIDATION_TYPE.MAX_LENGTH]: (label = '此字段', max) => `${label}长度不能超过${max}个字符`,
  [VALIDATION_TYPE.PATTERN]: (label = '此字段') => `${label}格式不正确`,
  [VALIDATION_TYPE.MIN]: (label = '此字段', min) => `${label}不能小于${min}`,
  [VALIDATION_TYPE.MAX]: (label = '此字段', max) => `${label}不能大于${max}`,
  [VALIDATION_TYPE.EMAIL]: (label = '此字段') => `${label}请输入正确的邮箱格式`,
  [VALIDATION_TYPE.PHONE]: (label = '此字段') => `${label}请输入正确的手机号格式`,
  [VALIDATION_TYPE.ID_CARD]: (label = '此字段') => `${label}请输入正确的身份证格式`,
  [VALIDATION_TYPE.CUSTOM]: (label = '此字段') => `${label}校验失败`,
};
