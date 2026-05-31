import 'dotenv/config';

jest.mock('@prisma/client', () => ({
  PrismaClient: jest.fn(),
}));

jest.mock('../../utils/cache', () => ({
  cacheService: {
    get: jest.fn(),
    set: jest.fn(),
    delete: jest.fn(),
  },
}));

jest.mock('../../utils/database', () => ({
  getPrismaClient: jest.fn(),
  disconnectDatabase: jest.fn(),
}));

jest.mock('../../config', () => ({
  config: {
    multisig: {
      defaultThreshold: 2,
      defaultOwners: [
        '0x742d35Cc6634C0532925a3b844Bc9e8588c10516',
        '0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199',
        '0x1aE0EA34a72D944a8C7603FfB3eC30a6669E454C',
      ],
    },
    server: {
      port: 3000,
      nodeEnv: 'test',
      isDevelopment: false,
    },
  },
}));

beforeEach(() => {
  jest.clearAllMocks();
});
