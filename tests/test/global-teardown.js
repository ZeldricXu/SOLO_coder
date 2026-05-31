module.exports = async () => {
  const duration = Date.now() - global.__TEST_START_TIME__;
  console.log('\n=== Test Suite Teardown ===');
  console.log(`Total duration: ${duration}ms`);
  console.log('===========================\n');
};
