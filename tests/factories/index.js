const teeFactory = require('./tee.factory');
const maskingFactory = require('./masking.factory');
const federatedFactory = require('./federated.factory');
const commonFactory = require('./common.factory');

module.exports = {
  ...teeFactory,
  ...maskingFactory,
  ...federatedFactory,
  ...commonFactory
};
