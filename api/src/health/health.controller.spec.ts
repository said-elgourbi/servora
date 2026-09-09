import { ServiceUnavailableException } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { DatabaseService } from '../database/database.service.js';
import { HealthController } from './health.controller.js';

describe('HealthController', () => {
  let controller: HealthController;
  const database = { ping: vi.fn() };

  beforeEach(async () => {
    const moduleRef = await Test.createTestingModule({
      controllers: [HealthController],
      providers: [{ provide: DatabaseService, useValue: database }],
    }).compile();

    controller = moduleRef.get(HealthController);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('reports ok when the database answers', async () => {
    database.ping.mockResolvedValueOnce(undefined);

    await expect(controller.check()).resolves.toEqual({
      status: 'ok',
      database: 'up',
    });
  });

  it('reports unavailable when the database cannot be reached', async () => {
    database.ping.mockRejectedValueOnce(new Error('connection refused'));

    await expect(controller.check()).rejects.toBeInstanceOf(
      ServiceUnavailableException,
    );
  });
});
