import React, { forwardRef, createContext, useContext, useCallback } from 'react';
import type { FieldValues, UseFormReturn, SubmitHandler, FieldPath } from 'react-hook-form';
import { FormProvider, useFormContext, Controller, useForm, useWatch } from 'react-hook-form';
import type { FormProps, FormFieldProps } from './types';
import { FormItem } from './FormItem';
import styles from './Form.module.css';
import { generateId } from '@a11y';

const FormContext = createContext<UseFormReturn<FieldValues> | undefined>(undefined);

export const useFormContextTyped = <T extends FieldValues>(): UseFormReturn<T> => {
  const context = useFormContext<T>();
  if (!context) {
    throw new Error('useFormContextTyped must be used within a Form component');
  }
  return context;
};

export const Form = forwardRef<HTMLFormElement, FormProps<FieldValues>>(
  ({ form, onSubmit, children, className, ...props }, ref) => {
    const formId = generateId('form');

    const handleSubmit = useCallback(
      async (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        await form.handleSubmit(onSubmit)(e);
      },
      [form, onSubmit],
    );

    return (
      <FormProvider {...form}>
        <FormContext.Provider value={form}>
          <form
            ref={ref}
            id={formId}
            className={`${styles.form} ${className || ''}`}
            onSubmit={handleSubmit}
            noValidate
            {...props}
          >
            {children}
          </form>
        </FormContext.Provider>
      </FormProvider>
    );
  },
);

export const FormField = <T extends FieldValues>({
  name,
  label,
  rules,
  children,
  required,
  helperText,
  disabled,
}: FormFieldProps<T>): React.ReactElement => {
  const { control, formState } = useFormContext<T>();

  return (
    <Controller
      name={name}
      control={control}
      rules={rules}
      render={({ field, fieldState }) => (
        <FormItem
          label={label}
          required={required}
          error={fieldState.error?.message}
          helperText={helperText}
          disabled={disabled}
        >
          {children({ field, fieldState, formState })}
        </FormItem>
      )}
    />
  );
};

Form.displayName = 'Form';
FormField.displayName = 'FormField';

export { FormItem, useForm, useFormContext, useWatch };
export type { FormProps, FormFieldProps, FormItemProps } from './types';
