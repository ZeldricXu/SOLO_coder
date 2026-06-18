import React, { Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import App from './App';
import Loading from '@/components/Loading';
import '@/index.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN}>
      <BrowserRouter>
        <Suspense fallback={<Loading />}>
          <App />
        </Suspense>
      </BrowserRouter>
    </ConfigProvider>
  </React.StrictMode>,
);
