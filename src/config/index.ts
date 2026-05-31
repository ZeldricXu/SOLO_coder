import dotenv from 'dotenv';
import { z } from 'zod';

dotenv.config();

const configSchema = z.object({
  PORT: z.coerce.number().default(3000),
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  DATABASE_URL: z.string().min(1),
  REDIS_HOST: z.string().default('localhost'),
  REDIS_PORT: z.coerce.number().default(6379),
  REDIS_PASSWORD: z.string().optional(),
  HD_WALLET_MNEMONIC: z.string().min(12),
  HD_WALLET_PASSPHRASE: z.string().optional(),
  ETH_RPC_URL: z.string().url().optional(),
  BSC_RPC_URL: z.string().url().optional(),
  POLYGON_RPC_URL: z.string().url().optional(),
  ARBITRUM_RPC_URL: z.string().url().optional(),
  OPTIMISM_RPC_URL: z.string().url().optional(),
  IPFS_GATEWAY_URL: z.string().url().default('https://ipfs.io'),
  IPFS_API_KEY: z.string().optional(),
  IPFS_API_SECRET: z.string().optional(),
  ARWEAVE_KEY: z.string().optional(),
  BRIDGE_CONTRACT_ADDRESS: z.string().optional(),
  RELAYER_PRIVATE_KEY: z.string().optional(),
  MULTISIG_THRESHOLD: z.coerce.number().default(2),
  MULTISIG_OWNERS: z.string().optional(),
  JWT_SECRET: z.string().min(32).default('your-jwt-secret-change-this-in-production'),
  API_KEY: z.string().optional(),
});

const validatedConfig = configSchema.parse(process.env);

export const config = {
  server: {
    port: validatedConfig.PORT,
    nodeEnv: validatedConfig.NODE_ENV,
    isDevelopment: validatedConfig.NODE_ENV === 'development',
    isProduction: validatedConfig.NODE_ENV === 'production',
  },
  database: {
    url: validatedConfig.DATABASE_URL,
  },
  redis: {
    host: validatedConfig.REDIS_HOST,
    port: validatedConfig.REDIS_PORT,
    password: validatedConfig.REDIS_PASSWORD,
  },
  wallet: {
    mnemonic: validatedConfig.HD_WALLET_MNEMONIC,
    passphrase: validatedConfig.HD_WALLET_PASSPHRASE,
  },
  chains: {
    eth: validatedConfig.ETH_RPC_URL,
    bsc: validatedConfig.BSC_RPC_URL,
    polygon: validatedConfig.POLYGON_RPC_URL,
    arbitrum: validatedConfig.ARBITRUM_RPC_URL,
    optimism: validatedConfig.OPTIMISM_RPC_URL,
  },
  storage: {
    ipfs: {
      gatewayUrl: validatedConfig.IPFS_GATEWAY_URL,
      apiKey: validatedConfig.IPFS_API_KEY,
      apiSecret: validatedConfig.IPFS_API_SECRET,
    },
    arweave: {
      keyPath: validatedConfig.ARWEAVE_KEY,
    },
  },
  bridge: {
    contractAddress: validatedConfig.BRIDGE_CONTRACT_ADDRESS,
    relayerPrivateKey: validatedConfig.RELAYER_PRIVATE_KEY,
  },
  multisig: {
    defaultThreshold: validatedConfig.MULTISIG_THRESHOLD,
    defaultOwners: validatedConfig.MULTISIG_OWNERS?.split(',').map(o => o.trim()) || [],
  },
  security: {
    jwtSecret: validatedConfig.JWT_SECRET,
    apiKey: validatedConfig.API_KEY,
  },
};

export default config;
