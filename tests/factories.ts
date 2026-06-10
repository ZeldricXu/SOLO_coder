import type { ButtonProps } from '@components/Button';
import type { SelectOption } from '@components/Select';
import type { Column } from '@components/Table';
import type { ToastOptions } from '@components/Toast';

export const createButtonProps = (
  overrides: Partial<ButtonProps> = {},
): ButtonProps => ({
  children: 'Test Button',
  variant: 'primary',
  size: 'md',
  ...overrides,
});

export const createSelectOptions = (count: number = 5): SelectOption[] => {
  return Array.from({ length: count }, (_, i) => ({
    value: `option-${i + 1}`,
    label: `Option ${i + 1}`,
    disabled: i === count - 1,
  }));
};

export interface TableRecord {
  id: number;
  name: string;
  age: number;
  email: string;
  status: 'active' | 'inactive' | 'pending';
  createdAt: string;
}

export const createTableColumns = (): Column<TableRecord>[] => [
  {
    key: 'id',
    title: 'ID',
    dataIndex: 'id',
    sortable: true,
    width: 80,
  },
  {
    key: 'name',
    title: 'Name',
    dataIndex: 'name',
    sortable: true,
    filterable: true,
  },
  {
    key: 'age',
    title: 'Age',
    dataIndex: 'age',
    sortable: true,
  },
  {
    key: 'email',
    title: 'Email',
    dataIndex: 'email',
    sortable: false,
  },
  {
    key: 'status',
    title: 'Status',
    dataIndex: 'status',
    filterable: true,
    filterOptions: [
      { label: 'Active', value: 'active' },
      { label: 'Inactive', value: 'inactive' },
      { label: 'Pending', value: 'pending' },
    ],
  },
  {
    key: 'createdAt',
    title: 'Created At',
    dataIndex: 'createdAt',
    sortable: true,
  },
];

export const createTableData = (count: number = 10): TableRecord[] => {
  const statuses: TableRecord['status'][] = ['active', 'inactive', 'pending'];
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    name: `User ${i + 1}`,
    age: Math.floor(Math.random() * 50) + 18,
    email: `user${i + 1}@example.com`,
    status: statuses[i % 3],
    createdAt: `2024-0${(i % 9) + 1}-${(i % 28) + 1}`,
  }));
};

export const createToastOptions = (
  overrides: Partial<ToastOptions> = {},
): ToastOptions => ({
  id: `toast-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
  type: 'info',
  message: 'Test message',
  duration: 3000,
  position: 'top-right',
  ...overrides,
});

export const createMultipleToasts = (count: number = 5): ToastOptions[] => {
  return Array.from({ length: count }, (_, i) =>
    createToastOptions({
      id: `toast-${i}`,
      message: `Toast message ${i + 1}`,
      type: (['info', 'success', 'warning', 'error', 'default'] as const)[i % 5],
    }),
  );
};

export const createCheckboxGroupOptions = () => [
  { label: 'Option 1', value: 'opt1' },
  { label: 'Option 2', value: 'opt2' },
  { label: 'Option 3', value: 'opt3' },
  { label: 'Option 4', value: 'opt4', disabled: true },
];

export const createFormSchema = () => ({
  name: { required: 'Name is required', minLength: { value: 2, message: 'Min 2 chars' } },
  email: { required: 'Email is required', pattern: { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: 'Invalid email' } },
  age: { required: 'Age is required', min: { value: 18, message: 'Must be 18+' } },
});

export const createValidFormData = () => ({
  name: 'John Doe',
  email: 'john@example.com',
  age: 25,
});

export const createInvalidFormData = () => ({
  name: 'J',
  email: 'invalid-email',
  age: 15,
});
