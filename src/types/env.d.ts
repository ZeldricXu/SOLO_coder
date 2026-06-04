declare global {
  namespace NodeJS {
    interface ProcessEnv {
      DATABASE_URL: string;
      NEXTAUTH_URL: string;
      NEXTAUTH_SECRET: string;
      JWT_SECRET: string;

      S3_ENDPOINT: string;
      S3_REGION: string;
      S3_ACCESS_KEY_ID: string;
      S3_SECRET_ACCESS_KEY: string;
      S3_BUCKET_NAME: string;
      S3_PUBLIC_URL?: string;

      FEISHU_APP_ID: string;
      FEISHU_APP_SECRET: string;

      NOTION_INTEGRATION_TOKEN: string;

      CONFLUENCE_BASE_URL: string;
      CONFLUENCE_EMAIL: string;
      CONFLUENCE_API_TOKEN: string;

      GITHUB_TOKEN: string;

      SYNC_INTERVAL_MINUTES: string;

      NODE_ENV: 'development' | 'production' | 'test';
    }
  }
}

export {};
