module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json'],
  transform: {
    '^.+\\.tsx?$': ['ts-jest', {
      tsconfig: 'tsconfig.json',
      useESM: false,
    }],
  },
  moduleNameMapper: {
    '^(\\.{1,2}/.*)\\.js$': '$1',
    '^@physics-sim/shared$': '<rootDir>/../shared/src/index.ts',
    '^@physics-sim/math$': '<rootDir>/../math/src/index.ts',
    '^@physics-sim/physics$': '<rootDir>/../physics/src/index.ts',
  },
};
