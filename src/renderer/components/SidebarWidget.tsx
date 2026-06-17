import React, { useState } from 'react';
import clsx from 'clsx';

interface SidebarWidgetProps {
  id: string;
  title: string;
  icon?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}

export const SidebarWidget: React.FC<SidebarWidgetProps> = ({
  id,
  title,
  icon,
  defaultOpen = true,
  children,
}) => {
  const [isOpen, setIsOpen] = useState(defaultOpen);
  
  return (
    <div className={clsx('sidebar-widget', { collapsed: !isOpen })}>
      <div
        className="sidebar-widget-header"
        onClick={() => setIsOpen(!isOpen)}
      >
        <div className="sidebar-widget-title">
          {icon && <span className="sidebar-widget-icon">{icon}</span>}
          <span>{title}</span>
        </div>
        <span className="sidebar-widget-toggle">▼</span>
      </div>
      <div
        className="sidebar-widget-content"
        style={{ maxHeight: isOpen ? '1000px' : '0' }}
      >
        {children}
      </div>
    </div>
  );
};

export default SidebarWidget;
