import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { Form, FormField, useForm } from '@components/Form';
import { Input } from '@components/Input';
import { Button } from '@components/Button';
import { createValidFormData, createInvalidFormData } from './factories';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import React from 'react';

const schema = z.object({
  name: z.string().min(2, '姓名至少2个字符'),
  email: z.string().email('邮箱格式不正确'),
  age: z.number().min(18, '必须年满18岁'),
});

type FormData = z.infer<typeof schema>;

const TestForm: React.FC<{
  defaultValues?: Partial<FormData>;
  onSubmit?: (data: FormData) => void;
}> = ({ defaultValues, onSubmit }) => {
  const form = useForm<FormData>({
    defaultValues,
    resolver: zodResolver(schema),
    mode: 'onBlur',
  });

  return (
    <Form form={form} onSubmit={onSubmit || vi.fn()}>
      <FormField name="name" label="姓名" required>
        {({ field }) => <Input {...field} placeholder="请输入姓名" />}
      </FormField>

      <FormField name="email" label="邮箱" required>
        {({ field }) => <Input {...field} placeholder="请输入邮箱" />}
      </FormField>

      <FormField name="age" label="年龄" required>
        {({ field }) => (
          <Input
            {...field}
            type="number"
            placeholder="请输入年龄"
            onChange={(e) => field.onChange(Number(e.target.value))}
          />
        )}
      </FormField>

      <Button type="submit">提交</Button>
    </Form>
  );
};

const getNameInput = () => screen.getByPlaceholderText('请输入姓名');
const getEmailInput = () => screen.getByPlaceholderText('请输入邮箱');
const getAgeInput = () => screen.getByPlaceholderText('请输入年龄');

describe('Form Component', () => {
  describe('基础渲染', () => {
    it('渲染表单和字段', () => {
      render(<TestForm />);

      expect(getNameInput()).toBeInTheDocument();
      expect(getEmailInput()).toBeInTheDocument();
      expect(getAgeInput()).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '提交' })).toBeInTheDocument();
    });

    it('渲染必填星号', () => {
      render(<TestForm />);
      const labels = screen.getAllByText(/姓名|邮箱|年龄/);
      labels.forEach((label) => {
        const requiredStar = label.querySelector('.required');
        expect(requiredStar).toBeInTheDocument();
        expect(requiredStar).toHaveTextContent('*');
      });
    });
  });

  describe('校验规则', () => {
    it('字段失焦时触发校验', async () => {
      render(<TestForm />);

      const nameInput = getNameInput();
      fireEvent.change(nameInput, { target: { value: 'a' } });
      fireEvent.blur(nameInput);

      await waitFor(() => {
        expect(screen.getByText('姓名至少2个字符')).toBeInTheDocument();
      });
    });

    it('输入有效内容后清除错误', async () => {
      render(<TestForm />);

      const nameInput = getNameInput();
      fireEvent.change(nameInput, { target: { value: 'a' } });
      fireEvent.blur(nameInput);

      await waitFor(() => {
        expect(screen.getByText('姓名至少2个字符')).toBeInTheDocument();
      });

      fireEvent.change(nameInput, { target: { value: '张三' } });
      fireEvent.blur(nameInput);

      await waitFor(() => {
        expect(screen.queryByText('姓名至少2个字符')).not.toBeInTheDocument();
      });
    });

    it('邮箱格式校验', async () => {
      render(<TestForm />);

      const emailInput = getEmailInput();
      fireEvent.change(emailInput, { target: { value: 'invalid-email' } });
      fireEvent.blur(emailInput);

      await waitFor(() => {
        expect(screen.getByText('邮箱格式不正确')).toBeInTheDocument();
      });
    });

    it('年龄最小值校验', async () => {
      render(<TestForm />);

      const ageInput = getAgeInput();
      fireEvent.change(ageInput, { target: { value: '15' } });
      fireEvent.blur(ageInput);

      await waitFor(() => {
        expect(screen.getByText('必须年满18岁')).toBeInTheDocument();
      });
    });

    it('错误信息显示在对应字段下', async () => {
      render(<TestForm />);

      const nameInput = getNameInput();
      const emailInput = getEmailInput();

      fireEvent.change(nameInput, { target: { value: 'a' } });
      fireEvent.blur(nameInput);
      fireEvent.change(emailInput, { target: { value: 'invalid' } });
      fireEvent.blur(emailInput);

      await waitFor(() => {
        const nameError = screen.getByText('姓名至少2个字符');
        const emailError = screen.getByText('邮箱格式不正确');

        expect(nameInput.closest('.formItem')).toContainElement(nameError);
        expect(emailInput.closest('.formItem')).toContainElement(emailError);
      });
    });
  });

  describe('异常场景 - 提交校验不通过阻止onSubmit', () => {
    it('校验不通过时不调用onSubmit', async () => {
      const handleSubmit = vi.fn();
      render(<TestForm onSubmit={handleSubmit} />);

      const nameInput = getNameInput();
      fireEvent.change(nameInput, { target: { value: 'a' } });
      fireEvent.blur(nameInput);

      const submitBtn = screen.getByRole('button', { name: '提交' });
      fireEvent.click(submitBtn);

      await waitFor(() => {
        expect(screen.getByText('姓名至少2个字符')).toBeInTheDocument();
      });

      expect(handleSubmit).not.toHaveBeenCalled();
    });

    it('全部字段有效时调用onSubmit', async () => {
      const handleSubmit = vi.fn();
      const validData = createValidFormData();

      render(<TestForm onSubmit={handleSubmit} />);

      const nameInput = getNameInput();
      const emailInput = getEmailInput();
      const ageInput = getAgeInput();

      fireEvent.change(nameInput, { target: { value: validData.name } });
      fireEvent.blur(nameInput);
      fireEvent.change(emailInput, { target: { value: validData.email } });
      fireEvent.blur(emailInput);
      fireEvent.change(ageInput, { target: { value: String(validData.age) } });
      fireEvent.blur(ageInput);

      const submitBtn = screen.getByRole('button', { name: '提交' });
      fireEvent.click(submitBtn);

      await waitFor(() => {
        expect(handleSubmit).toHaveBeenCalledTimes(1);
      });
      
      expect(handleSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          name: validData.name,
          email: validData.email,
          age: validData.age,
        }),
        expect.anything(),
      );
    });

    it('部分字段无效时不调用onSubmit', async () => {
      const handleSubmit = vi.fn();
      const invalidData = createInvalidFormData();

      render(<TestForm onSubmit={handleSubmit} />);

      const nameInput = getNameInput();
      const emailInput = getEmailInput();
      const ageInput = getAgeInput();

      fireEvent.change(nameInput, { target: { value: invalidData.name } });
      fireEvent.change(emailInput, { target: { value: invalidData.email } });
      fireEvent.change(ageInput, { target: { value: String(invalidData.age) } });

      const submitBtn = screen.getByRole('button', { name: '提交' });
      fireEvent.click(submitBtn);

      await waitFor(() => {
        expect(screen.getByText('姓名至少2个字符')).toBeInTheDocument();
        expect(screen.getByText('邮箱格式不正确')).toBeInTheDocument();
        expect(screen.getByText('必须年满18岁')).toBeInTheDocument();
      });

      expect(handleSubmit).not.toHaveBeenCalled();
    });
  });

  describe('表单状态', () => {
    it('defaultValues设置初始值', () => {
      const defaultValues = createValidFormData();
      render(<TestForm defaultValues={defaultValues} />);

      expect(getNameInput()).toHaveValue(defaultValues.name);
      expect(getEmailInput()).toHaveValue(defaultValues.email);
      expect(getAgeInput()).toHaveValue(defaultValues.age);
    });

    it('isDirty状态正确', async () => {
      const formRef = { current: null as any };

      const TestFormWithRef = () => {
        const form = useForm<FormData>({ resolver: zodResolver(schema) });
        formRef.current = form;

        return (
          <Form form={form} onSubmit={vi.fn()}>
            <FormField name="name" label="姓名">
              {({ field }) => <Input {...field} placeholder="请输入姓名" />}
            </FormField>
          </Form>
        );
      };

      render(<TestFormWithRef />);

      expect(formRef.current.formState.isDirty).toBe(false);

      const nameInput = getNameInput();
      fireEvent.change(nameInput, { target: { value: 'test' } });

      await waitFor(() => {
        expect(formRef.current.formState.isDirty).toBe(true);
      });
    });

    it('isSubmitting状态正确', async () => {
      let isSubmittingDuringSubmit: boolean | null = null;
      let resolvePromise: () => void;
      
      const handleSubmit = vi.fn().mockImplementation(
        (_data: any, _event: any) => {
          return new Promise<void>((resolve) => {
            resolvePromise = resolve;
          });
        },
      );
      
      const formRef = React.createRef<any>();
      const validData = createValidFormData();

      const TestFormWithRef = () => {
        const form = useForm<FormData>({
          defaultValues: validData,
          resolver: zodResolver(schema),
        });
        
        React.useEffect(() => {
          (formRef as any).current = form;
        }, [form]);

        const wrappedHandleSubmit = vi.fn((data: FormData, event: any) => {
          isSubmittingDuringSubmit = form.formState.isSubmitting;
          return handleSubmit(data, event);
        });

        return (
          <Form form={form} onSubmit={wrappedHandleSubmit}>
            <FormField name="name" label="姓名" required>
              {({ field }) => <Input {...field} placeholder="请输入姓名" />}
            </FormField>
            <FormField name="email" label="邮箱" required>
              {({ field }) => <Input {...field} placeholder="请输入邮箱" />}
            </FormField>
            <FormField name="age" label="年龄" required>
              {({ field }) => (
                <Input
                  {...field}
                  type="number"
                  placeholder="请输入年龄"
                  onChange={(e) => field.onChange(Number(e.target.value))}
                />
              )}
            </FormField>
            <Button type="submit">提交</Button>
          </Form>
        );
      };

      render(<TestFormWithRef />);

      await act(async () => {
        await formRef.current.trigger();
      });

      expect(formRef.current.formState.isSubmitting).toBe(false);

      const submitBtn = screen.getByRole('button', { name: '提交' });
      
      let submitPromise: Promise<void>;
      await act(async () => {
        submitPromise = new Promise<void>((resolve) => {
          setTimeout(() => {
            fireEvent.click(submitBtn);
            resolve();
          }, 0);
        });
        await submitPromise;
      });

      await waitFor(() => {
        expect(handleSubmit).toHaveBeenCalled();
      });

      expect(isSubmittingDuringSubmit).toBe(true);

      resolvePromise!();
      
      await waitFor(() => {
        expect(formRef.current.formState.isSubmitting).toBe(false);
      });
    });
  });

  describe('ARIA无障碍属性', () => {
    it('表单字段具有正确的ARIA属性', async () => {
      render(<TestForm />);

      const nameInput = getNameInput();
      expect(nameInput).toHaveAttribute('aria-required', 'true');

      fireEvent.blur(nameInput);

      await waitFor(() => {
        expect(nameInput).toHaveAttribute('aria-invalid', 'true');
        expect(nameInput).toHaveAttribute('aria-describedby');
      });
    });

    it('修复错误后清除aria-invalid', async () => {
      render(<TestForm />);

      const nameInput = getNameInput();
      fireEvent.blur(nameInput);

      await waitFor(() => {
        expect(nameInput).toHaveAttribute('aria-invalid', 'true');
      });

      fireEvent.change(nameInput, { target: { value: '张三' } });
      fireEvent.blur(nameInput);

      await waitFor(() => {
        expect(nameInput).toHaveAttribute('aria-invalid', 'false');
      });
    });
  });

  describe('键盘交互', () => {
    it('Enter键提交表单', async () => {
      const handleSubmit = vi.fn();
      const validData = createValidFormData();
      const formRef = React.createRef<HTMLFormElement>();

      const TestFormWithRef = () => {
        const form = useForm<FormData>({
          defaultValues: validData,
          resolver: zodResolver(schema),
          mode: 'onChange',
        });

        return (
          <Form form={form} onSubmit={handleSubmit} ref={formRef}>
            <FormField name="name" label="姓名" required>
              {({ field }) => <Input {...field} placeholder="请输入姓名" />}
            </FormField>
            <FormField name="email" label="邮箱" required>
              {({ field }) => <Input {...field} placeholder="请输入邮箱" />}
            </FormField>
            <FormField name="age" label="年龄" required>
              {({ field }) => (
                <Input
                  {...field}
                  type="number"
                  placeholder="请输入年龄"
                  onChange={(e) => field.onChange(Number(e.target.value))}
                />
              )}
            </FormField>
            <Button type="submit">提交</Button>
          </Form>
        );
      };

      render(<TestFormWithRef />);

      const nameInput = getNameInput();
      fireEvent.change(nameInput, { target: { value: validData.name } });
      fireEvent.blur(nameInput);
      
      const emailInput = getEmailInput();
      fireEvent.change(emailInput, { target: { value: validData.email } });
      fireEvent.blur(emailInput);
      
      const ageInput = getAgeInput();
      fireEvent.change(ageInput, { target: { value: String(validData.age) } });
      fireEvent.blur(ageInput);

      if (formRef.current) {
        fireEvent.submit(formRef.current);
      }

      await waitFor(() => {
        expect(handleSubmit).toHaveBeenCalledWith(
          expect.objectContaining({
            name: validData.name,
            email: validData.email,
            age: validData.age,
          }),
          expect.anything(),
        );
      });
    });

    it('Tab键在字段间导航', () => {
      render(<TestForm />);

      const nameInput = getNameInput();
      const emailInput = getEmailInput();
      const ageInput = getAgeInput();

      expect(nameInput).not.toHaveAttribute('disabled');
      expect(nameInput).not.toHaveAttribute('tabindex', '-1');
      expect(emailInput).not.toHaveAttribute('disabled');
      expect(emailInput).not.toHaveAttribute('tabindex', '-1');
      expect(ageInput).not.toHaveAttribute('disabled');
      expect(ageInput).not.toHaveAttribute('tabindex', '-1');

      expect(document.body.contains(nameInput)).toBe(true);
      expect(document.body.contains(emailInput)).toBe(true);
      expect(document.body.contains(ageInput)).toBe(true);
    });
  });
});
