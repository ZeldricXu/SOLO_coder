import React from 'react';
import { Spin } from 'antd';

interface LoadingProps {
  size?: 'small' | 'default' | 'large';
  tip?: string;
  fullScreen?: boolean;
}

const Loading: React.FC<LoadingProps> = ({ size = 'large', tip, fullScreen = false }) => {
  if (fullScreen) {
    return (
      <div className="full-height flex-center">
        <Spin size={size} tip={tip} />
      </div>
    );
  }
  return <Spin size={size} tip={tip} />;
};

export default Loading;
