import { startup } from './boot/startup';

startup().catch((err) => {
  console.error('💥 Failed to start application:', err);
  process.exit(1);
});
