import type {
  FieldValues,
  UseFormReturn,
  SubmitHandler,
  FieldPath,
  RegisterOptions,
} from 'react-hook-form';
import type { ZodSchema, ZodTypeDef } from 'zod';

export interface FormProps<T extends FieldValues> extends React.FormHTMLAttributes<HTMLFormElement> {
  form: UseFormReturn<T>;
  onSubmit: SubmitHandler<T>;
  children: React.ReactNode;
  schema?: ZodSchema<T, ZodTypeDef, unknown>;
  showErrorOnSubmit?: boolean;
}

export interface FormFieldProps<T extends FieldValues> {
  name: FieldPath<T>;
  label?: React.ReactNode;
  rules?: RegisterOptions<T>;
  children: (props: {
    field: ReturnType<UseFormReturn<T>['register']>;
    fieldState: {
      error?: { message?: string };
      isDirty: boolean;
      isTouched: boolean;
      invalid: boolean;
    };
    formState: UseFormReturn<T>['formState'];
  }) => React.ReactElement;
  required?: boolean;
  helperText?: string;
  disabled?: boolean;
}

export interface FormItemProps {
  label?: React.ReactNode;
  required?: boolean;
  error?: string;
  helperText?: string;
  disabled?: boolean;
  children: React.ReactElement;
  htmlFor?: string;
}
