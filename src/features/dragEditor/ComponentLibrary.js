import React from 'react';
import { Card, Typography } from 'antd';
import {
  FontSizeOutlined,
  AlignLeftOutlined,
  NumberOutlined,
  DownCircleOutlined,
  CalendarOutlined,
  UploadOutlined,
  StarOutlined,
  CheckCircleOutlined,
  CheckSquareOutlined,
  SwitcherOutlined,
} from '@ant-design/icons';
import DraggableComponent from './DraggableComponent';
import { COMPONENT_CONFIGS, COMPONENT_TYPE } from '../componentLibrary';

const { Title } = Typography;

const ICON_MAP = {
  FontSizeOutlined,
  AlignLeftOutlined,
  NumberOutlined,
  DownCircleOutlined,
  CalendarOutlined,
  UploadOutlined,
  StarOutlined,
  CheckCircleOutlined,
  CheckSquareOutlined,
  SwitcherOutlined,
};

const ComponentLibrary = () => {
  const componentTypes = Object.entries(COMPONENT_CONFIGS);

  const basicComponents = componentTypes.filter(([type]) =>
    [
      COMPONENT_TYPE.TEXT_INPUT,
      COMPONENT_TYPE.TEXT_AREA,
      COMPONENT_TYPE.NUMBER_INPUT,
      COMPONENT_TYPE.SELECT,
      COMPONENT_TYPE.RADIO,
      COMPONENT_TYPE.CHECKBOX,
    ].includes(type)
  );

  const advancedComponents = componentTypes.filter(([type]) =>
    [
      COMPONENT_TYPE.DATE_PICKER,
      COMPONENT_TYPE.FILE_UPLOAD,
      COMPONENT_TYPE.RATING,
      COMPONENT_TYPE.SWITCH,
    ].includes(type)
  );

  return (
    <div>
      <Card
        size="small"
        title="基础组件"
        style={{ marginBottom: 16 }}
      >
        {basicComponents.map(([type, config]) => {
          const Icon = ICON_MAP[config.icon];
          return (
            <DraggableComponent
              key={type}
              componentType={type}
              componentConfig={config}
              icon={Icon}
            />
          );
        })}
      </Card>

      <Card
        size="small"
        title="高级组件"
      >
        {advancedComponents.map(([type, config]) => {
          const Icon = ICON_MAP[config.icon];
          return (
            <DraggableComponent
              key={type}
              componentType={type}
              componentConfig={config}
              icon={Icon}
            />
          );
        })}
      </Card>
    </div>
  );
};

export default ComponentLibrary;
