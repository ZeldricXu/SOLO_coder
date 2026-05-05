import TextInputComponent from './components/TextInputComponent';
import TextAreaComponent from './components/TextAreaComponent';
import NumberInputComponent from './components/NumberInputComponent';
import SelectComponent from './components/SelectComponent';
import DatePickerComponent from './components/DatePickerComponent';
import FileUploadComponent from './components/FileUploadComponent';
import RatingComponent from './components/RatingComponent';
import RadioComponent from './components/RadioComponent';
import CheckboxComponent from './components/CheckboxComponent';
import SwitchComponent from './components/SwitchComponent';

export const COMPONENT_TYPE = {
  TEXT_INPUT: 'text_input',
  TEXT_AREA: 'text_area',
  NUMBER_INPUT: 'number_input',
  SELECT: 'select',
  DATE_PICKER: 'date_picker',
  FILE_UPLOAD: 'file_upload',
  RATING: 'rating',
  RADIO: 'radio',
  CHECKBOX: 'checkbox',
  SWITCH: 'switch',
};

export const COMPONENT_MAP = {
  [COMPONENT_TYPE.TEXT_INPUT]: TextInputComponent,
  [COMPONENT_TYPE.TEXT_AREA]: TextAreaComponent,
  [COMPONENT_TYPE.NUMBER_INPUT]: NumberInputComponent,
  [COMPONENT_TYPE.SELECT]: SelectComponent,
  [COMPONENT_TYPE.DATE_PICKER]: DatePickerComponent,
  [COMPONENT_TYPE.FILE_UPLOAD]: FileUploadComponent,
  [COMPONENT_TYPE.RATING]: RatingComponent,
  [COMPONENT_TYPE.RADIO]: RadioComponent,
  [COMPONENT_TYPE.CHECKBOX]: CheckboxComponent,
  [COMPONENT_TYPE.SWITCH]: SwitchComponent,
};

export const COMPONENT_CONFIGS = {
  [COMPONENT_TYPE.TEXT_INPUT]: {
    label: '文本输入',
    icon: 'FontSizeOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.TEXT_INPUT,
      label: '文本输入',
      placeholder: '请输入',
      required: false,
      validation: {
        min_length: 0,
        max_length: 100,
        pattern: '',
      },
    },
    editableProps: ['label', 'placeholder', 'required', 'validation'],
  },
  [COMPONENT_TYPE.TEXT_AREA]: {
    label: '多行文本',
    icon: 'AlignLeftOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.TEXT_AREA,
      label: '多行文本',
      placeholder: '请输入',
      required: false,
      validation: {
        min_length: 0,
        max_length: 500,
      },
    },
    editableProps: ['label', 'placeholder', 'required', 'validation'],
  },
  [COMPONENT_TYPE.NUMBER_INPUT]: {
    label: '数字输入',
    icon: 'NumberOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.NUMBER_INPUT,
      label: '数字输入',
      placeholder: '请输入数字',
      required: false,
      validation: {
        min: undefined,
        max: undefined,
        step: 1,
      },
      prefix: '',
      suffix: '',
    },
    editableProps: ['label', 'placeholder', 'required', 'validation', 'prefix', 'suffix'],
  },
  [COMPONENT_TYPE.SELECT]: {
    label: '下拉选择',
    icon: 'DownCircleOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.SELECT,
      label: '下拉选择',
      placeholder: '请选择',
      required: false,
      multiple: false,
      options: [
        { value: 'option1', label: '选项1' },
        { value: 'option2', label: '选项2' },
      ],
    },
    editableProps: ['label', 'placeholder', 'required', 'multiple', 'options'],
  },
  [COMPONENT_TYPE.DATE_PICKER]: {
    label: '日期选择',
    icon: 'CalendarOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.DATE_PICKER,
      label: '日期选择',
      placeholder: '请选择日期',
      required: false,
      date_type: 'date',
    },
    editableProps: ['label', 'placeholder', 'required', 'date_type'],
  },
  [COMPONENT_TYPE.FILE_UPLOAD]: {
    label: '文件上传',
    icon: 'UploadOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.FILE_UPLOAD,
      label: '文件上传',
      placeholder: '请上传文件',
      required: false,
      multiple: false,
      file_types: [],
      max_size: 10,
    },
    editableProps: ['label', 'placeholder', 'required', 'multiple', 'file_types', 'max_size'],
  },
  [COMPONENT_TYPE.RATING]: {
    label: '评分',
    icon: 'StarOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.RATING,
      label: '评分',
      required: false,
      max_value: 5,
      allow_half: false,
    },
    editableProps: ['label', 'required', 'max_value', 'allow_half'],
  },
  [COMPONENT_TYPE.RADIO]: {
    label: '单选框',
    icon: 'CheckCircleOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.RADIO,
      label: '单选框',
      required: false,
      button_style: false,
      options: [
        { value: 'option1', label: '选项1' },
        { value: 'option2', label: '选项2' },
      ],
    },
    editableProps: ['label', 'required', 'button_style', 'options'],
  },
  [COMPONENT_TYPE.CHECKBOX]: {
    label: '多选框',
    icon: 'CheckSquareOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.CHECKBOX,
      label: '多选框',
      required: false,
      options: [
        { value: 'option1', label: '选项1' },
        { value: 'option2', label: '选项2' },
      ],
    },
    editableProps: ['label', 'required', 'options'],
  },
  [COMPONENT_TYPE.SWITCH]: {
    label: '开关',
    icon: 'SwitcherOutlined',
    defaultConfig: {
      component_type: COMPONENT_TYPE.SWITCH,
      label: '开关',
      required: false,
      checked_text: '开启',
      unchecked_text: '关闭',
    },
    editableProps: ['label', 'required', 'checked_text', 'unchecked_text'],
  },
};

export {
  TextInputComponent,
  TextAreaComponent,
  NumberInputComponent,
  SelectComponent,
  DatePickerComponent,
  FileUploadComponent,
  RatingComponent,
  RadioComponent,
  CheckboxComponent,
  SwitchComponent,
};
