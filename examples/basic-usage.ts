import { bootstrap, shutdown, createDefaultConfig } from '@application/bootstrap';
import { createServer, startServer, stopServer } from '@api/server';
import type { AppContainer } from '@application/container';

async function main(): Promise<void> {
  const config = createDefaultConfig();
  const container: AppContainer = await bootstrap(config);

  const logger = container.logger.child({ module: 'main' });

  try {
    logger.info('Starting application...');

    const chainClient = container.chainInteraction.getClient(1);

    const blockNumber = await chainClient.getBlockNumber();
    logger.info('Current block number', { blockNumber: blockNumber.toString() });

    const gasPrices = await container.gasEstimator.getCurrentGasPrice(1);
    logger.info('Current gas prices', {
      standard: {
        maxFee: gasPrices.standard.maxFeePerGas.toString(),
        priorityFee: gasPrices.standard.maxPriorityFeePerGas.toString(),
      },
    });

    const estimate = await container.gasEstimator.estimate({
      chainId: 1,
      to: '0x0000000000000000000000000000000000000001',
      value: 1000000000000000000n,
      speed: 'standard',
    });

    logger.info('Gas estimate', {
      gasLimit: estimate.gasLimit.toString(),
      maxFee: estimate.maxFeePerGas.toString(),
      estimatedCost: estimate.estimatedCost.toString(),
      confidence: estimate.confidence,
    });

    const builtTx = await container.transactionBuilder.buildTransaction({
      chainId: 1,
      from: '0x0000000000000000000000000000000000000002',
      to: '0x0000000000000000000000000000000000000001',
      value: estimate.estimatedCost,
      gasLimit: estimate.gasLimit,
      maxFeePerGas: estimate.maxFeePerGas,
      maxPriorityFeePerGas: estimate.maxPriorityFeePerGas,
    });

    logger.info('Transaction built', {
      hash: builtTx.transactionHash,
      to: builtTx.transaction.to,
      value: builtTx.transaction.value.toString(),
    });

    const server = createServer(container, container.logger, {
      port: 3000,
      host: '0.0.0.0',
      enableCors: true,
      enableRequestLogging: true,
    });

    const { server: httpServer, port } = await startServer(server, {
      port: 3000,
      host: '0.0.0.0',
      enableCors: true,
      enableRequestLogging: true,
    }, container.logger);

    logger.info('API server started', {
      url: `http://localhost:${port}`,
      endpoints: {
        health: `http://localhost:${port}/api/v1/health`,
        gas: `http://localhost:${port}/api/v1/gas/price/1`,
        estimate: `http://localhost:${port}/api/v1/gas/estimate`,
      },
    });

    const handleShutdown = async () => {
      logger.info('Shutting down gracefully...');
      await stopServer(httpServer, container.logger);
      await shutdown(container);
      process.exit(0);
    };

    process.on('SIGINT', handleShutdown);
    process.on('SIGTERM', handleShutdown);
  } catch (error) {
    logger.error('Application failed', {
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined,
    });
    await shutdown(container);
    process.exit(1);
  }
}

if (require.main === module) {
  main();
}
