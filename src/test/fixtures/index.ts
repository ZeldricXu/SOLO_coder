export * from './drawingFixtures';
export * from './sceneFixtures';
export * from './lightingFixtures';
export * from './ioFixtures';
export * from './concurrencyFixtures';
export * from './sketchfabFixtures';

import drawingTestFixtures from './drawingFixtures';
import sceneTestFixtures from './sceneFixtures';
import lightingTestFixtures from './lightingFixtures';
import ioTestFixtures from './ioFixtures';
import concurrencyTestFixtures from './concurrencyFixtures';
import sketchfabTestFixtures from './sketchfabFixtures';

export const testFixtures = {
  drawing: drawingTestFixtures,
  scene: sceneTestFixtures,
  lighting: lightingTestFixtures,
  io: ioTestFixtures,
  concurrency: concurrencyTestFixtures,
  sketchfab: sketchfabTestFixtures,
};

export default testFixtures;
