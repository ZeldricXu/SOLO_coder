'use client';

import './globals.css';
import { Inter } from 'next/font/google';
import { ThemeProvider } from '@/components/providers/ThemeProvider';
import { TRPCProvider } from '@/components/providers/TRPCProvider';
import { ToastProvider } from '@/components/providers/ToastProvider';
import { cn } from '@/lib/utils';

const inter = Inter({ subsets: ['latin'], variable: '--font-sans' });

export const metadata = {
  title: {
    default: 'Knowledge Hub - 企业知识管理平台',
    template: '%s | Knowledge Hub',
  },
  description:
    'Knowledge Hub 是一个现代化的企业知识管理平台，支持文档协作、版本控制、智能搜索和多源数据同步。',
  keywords: ['知识管理', '文档协作', '知识库', '企业wiki', '版本控制'],
  authors: [{ name: 'Knowledge Hub Team' }],
  creator: 'Knowledge Hub',
  viewport: {
    width: 'device-width',
    initialScale: 1,
    maximumScale: 5,
  },
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: 'white' },
    { media: '(prefers-color-scheme: dark)', color: 'black' },
  ],
  openGraph: {
    type: 'website',
    locale: 'zh_CN',
    url: 'https://knowledge-hub.example.com',
    siteName: 'Knowledge Hub',
    title: 'Knowledge Hub - 企业知识管理平台',
    description:
      '现代化的企业知识管理平台，支持文档协作、版本控制、智能搜索',
    images: [
      {
        url: '/og.png',
        width: 1200,
        height: 630,
        alt: 'Knowledge Hub',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Knowledge Hub - 企业知识管理平台',
    description:
      '现代化的企业知识管理平台，支持文档协作、版本控制、智能搜索',
    images: ['/og.png'],
    creator: '@knowledgehub',
  },
  icons: {
    icon: '/favicon.ico',
    shortcut: '/favicon-16x16.png',
    apple: '/apple-touch-icon.png',
  },
  manifest: '/site.webmanifest',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body
        className={cn(
          'min-h-screen bg-background font-sans antialiased',
          inter.variable
        )}
      >
        <ThemeProvider
          attribute="class"
          defaultTheme="system"
          enableSystem
          disableTransitionOnChange
        >
          <TRPCProvider>
            <ToastProvider>{children}</ToastProvider>
          </TRPCProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
