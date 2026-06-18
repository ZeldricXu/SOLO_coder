export const getDatabase = jest.fn().mockReturnValue({
  prepare: jest.fn().mockReturnThis(),
  run: jest.fn(),
  get: jest.fn(),
  all: jest.fn().mockReturnValue([]),
  exec: jest.fn(),
  pragma: jest.fn(),
  close: jest.fn(),
});
