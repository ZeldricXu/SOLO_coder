export interface AriaButtonProps {
  role?: 'button';
  tabIndex?: 0 | -1;
  'aria-disabled'?: boolean;
  'aria-label'?: string;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  'aria-pressed'?: boolean | 'mixed';
  'aria-expanded'?: boolean;
  'aria-haspopup'?: boolean | 'menu' | 'listbox' | 'tree' | 'grid' | 'dialog';
  'aria-controls'?: string;
}

export interface AriaInputProps {
  'aria-invalid'?: boolean;
  'aria-required'?: boolean;
  'aria-disabled'?: boolean;
  'aria-readonly'?: boolean;
  'aria-label'?: string;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  'aria-placeholder'?: string;
  'aria-errormessage'?: string;
}

export interface AriaDialogProps {
  role: 'dialog' | 'alertdialog';
  'aria-modal': boolean;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  'aria-label'?: string;
}

export interface AriaMenuProps {
  role: 'menu';
  'aria-labelledby'?: string;
  'aria-label'?: string;
}

export interface AriaMenuItemProps {
  role: 'menuitem' | 'menuitemcheckbox' | 'menuitemradio';
  'aria-disabled'?: boolean;
  'aria-checked'?: boolean | 'mixed';
  tabIndex?: 0 | -1;
}

export const getButtonAriaProps = (
  disabled: boolean,
  label?: string,
  pressed?: boolean,
): AriaButtonProps => ({
  role: 'button',
  tabIndex: disabled ? -1 : 0,
  'aria-disabled': disabled,
  'aria-label': label,
  'aria-pressed': pressed,
});

export const getInputAriaProps = (
  disabled: boolean,
  required: boolean,
  invalid: boolean,
  errorMessage?: string,
): AriaInputProps => ({
  'aria-disabled': disabled,
  'aria-required': required,
  'aria-invalid': invalid,
  'aria-errormessage': errorMessage,
});

export const getDialogAriaProps = (
  isAlert: boolean,
  titleId?: string,
  descriptionId?: string,
): AriaDialogProps => ({
  role: isAlert ? 'alertdialog' : 'dialog',
  'aria-modal': true,
  'aria-labelledby': titleId,
  'aria-describedby': descriptionId,
});

export const getLiveRegionProps = (polite: boolean = true) => ({
  'aria-live': polite ? 'polite' : 'assertive',
  'aria-atomic': true,
});

export const getMenuAriaProps = (
  isOpen: boolean,
  label?: string,
  labelledBy?: string,
): AriaMenuProps => ({
  role: 'menu',
  'aria-label': label,
  'aria-labelledby': labelledBy,
});

export const getMenuItemAriaProps = (
  disabled: boolean,
  type?: 'menu' | 'checkbox' | 'radio',
  checked?: boolean | 'mixed',
): AriaMenuItemProps => ({
  role: type === 'checkbox' ? 'menuitemcheckbox' : type === 'radio' ? 'menuitemradio' : 'menuitem',
  'aria-disabled': disabled,
  'aria-checked': type === 'checkbox' || type === 'radio' ? checked : undefined,
  tabIndex: disabled ? -1 : 0,
});

export const generateId = (prefix: string): string => {
  return `${prefix}-${Math.random().toString(36).substring(2, 11)}`;
};
