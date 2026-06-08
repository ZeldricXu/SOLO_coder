export * from './drawingFixtures';
export * from './sceneFixtures';
export * from './lightingFixtures';
export * from './ioFixtures';
export * from './concurrencyFixtures';

import drawingTestFixtures from './drawingFixtures';
import sceneTestFixtures from './sceneFixtures';
import lightingTestFixtures from './lightingFixtures';
import ioTestFixtures from './ioFixtures';
import concurrencyTestFixtures from './concurrencyFixtures';

export const testFixtures = {
  drawing: drawingTestFixtures,
  scene: sceneTestFixtures,
  lighting: lightingTestFixtures,
  io: ioTestFixtures,
  concurrency: concurrencyTestFixtures,
};

export default testFixtures;
