'use client';

import * as React from 'react';
import { Toaster, toast as sonnerToast } from 'sonner';
import {
  ToastProvider as RadixToastProvider,
  ToastViewport,
  toast as radixToast,
} from '@/components/ui/toast';

interface ToastProviderProps {
  children: React.ReactNode;
  useSonner?: boolean;
}

function ToastProvider({ children, useSonner = true }: ToastProviderProps) {
  if (useSonner) {
    return (
      <>
        {children}
        <Toaster position="top-right" />
      </>
    );
  }

  return (
    <RadixToastProvider swipeDirection="right">
      {children}
      <ToastViewport />
    </RadixToastProvider>
  );
}

function useToast() {
  const radix = radixToast();
  return {
    toast: sonnerToast,
    toasts: radix.toasts,
    dismiss: radix.dismiss,
  };
}

export { ToastProvider, useToast };
