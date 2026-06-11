import React, { forwardRef, createContext, useContext, useCallback, useMemo, useRef } from 'react';
import type { FieldValues, UseFormReturn, SubmitHandler, FieldPath } from 'react-hook-form';
import { FormProvider, useFormContext, Controller, useForm, useWatch } from 'react-hook-form';
import type { FormProps, FormFieldProps } from './types';
import { FormItem } from './FormItem';
import { ValidationEngine } from '@validation';
import type { FieldSchema, ValidationResult } from '@validation';
import styles from './Form.module.css';
import { generateId } from '@a11y';

const FormContext = createContext<UseFormReturn<FieldValues> | undefined>(undefined);

const ValidationEngineContext = createContext<ValidationEngine | undefined>(undefined);

export const useValidationEngine = (): ValidationEngine | undefined => {
  return useContext(ValidationEngineContext);
};

export const useFormContextTyped = <T extends FieldValues>(): UseFormReturn<T> => {
  const context = useFormContext<T>();
  if (!context) {
    throw new Error('useFormContextTyped must be used within a Form component');
  }
  return context;
};

export const Form = forwardRef<HTMLFormElement, FormProps<FieldValues>>(
  ({ form, onSubmit, children, className, fieldSchemas, ...props }, ref) => {
    const formId = generateId('form');
    const engineRef = useRef<ValidationEngine | undefined>(undefined);

    const validationEngine = useMemo(() => {
      if (fieldSchemas && fieldSchemas.length > 0) {
        const engine = new ValidationEngine(fieldSchemas);
        engineRef.current = engine;
        return engine;
      }
      return undefined;
    }, [fieldSchemas]);

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
          <ValidationEngineContext.Provider value={validationEngine}>
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
          </ValidationEngineContext.Provider>
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
  const validationEngine = useContext(ValidationEngineContext);

  const fieldSchema: FieldSchema | undefined = useMemo(() => {
    if (!validationEngine) return undefined;
    return validationEngine.getSchemas().find((s) => s.name === name);
  }, [validationEngine, name]);

  const mergedRules = useMemo(() => {
    if (!fieldSchema) return rules;

    const engineRules: Record<string, unknown> = {};
    for (const rule of fieldSchema.rules || []) {
      switch (rule.type) {
        case 'required':
          engineRules.required = rule.message || true;
          break;
        case 'minLength':
          engineRules.minLength = { value: rule.value as number, message: rule.message };
          break;
        case 'maxLength':
          engineRules.maxLength = { value: rule.value as number, message: rule.message };
          break;
        case 'min':
          engineRules.min = { value: rule.value as number, message: rule.message };
          break;
        case 'max':
          engineRules.max = { value: rule.value as number, message: rule.message };
          break;
        case 'pattern':
          engineRules.pattern = { value: rule.value as RegExp, message: rule.message };
          break;
        case 'email':
          engineRules.pattern = { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: rule.message || '邮箱格式不正确' };
          break;
        default:
          break;
      }
    }

    return { ...engineRules, ...rules };
  }, [fieldSchema, rules]);

  return (
    <Controller
      name={name}
      control={control}
      rules={mergedRules}
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
export type { FieldSchema, ValidationResult } from '@validation';
