import { describe, expect, it, vi } from 'vitest';
import { SmsDeliveryError } from '../sms-provider.js';
import { SinchSmsProvider, type FetchLike } from './sinch-sms-provider.js';

const CONFIG = {
  servicePlanId: 'the-service-plan',
  apiToken: 'the-api-token',
  from: 'Servora',
};

const MESSAGE = {
  to: '+15145550100',
  body: 'Servora verification code: 123456.',
};

function fetchReturning(status: number): FetchLike {
  return () => Promise.resolve(new Response('{}', { status }));
}

describe('SinchSmsProvider', () => {
  it('posts the message to the Sinch batch endpoint for its service plan', async () => {
    const fetchImplementation = vi.fn(fetchReturning(201));
    const provider = new SinchSmsProvider(CONFIG, fetchImplementation);

    await provider.send(MESSAGE);

    expect(fetchImplementation).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImplementation.mock.calls[0] ?? [];
    expect(url).toBe(
      'https://sms.api.sinch.com/xms/v1/the-service-plan/batches',
    );
    expect(init?.method).toBe('POST');
    expect(init?.headers['authorization']).toBe('Bearer the-api-token');
    expect(init?.headers['content-type']).toBe('application/json');
    expect(JSON.parse(init?.body ?? '{}')).toEqual({
      from: 'Servora',
      to: ['+15145550100'],
      body: 'Servora verification code: 123456.',
    });
  });

  it('bounds the request with a timeout signal', async () => {
    const fetchImplementation = vi.fn(fetchReturning(201));
    const provider = new SinchSmsProvider(CONFIG, fetchImplementation);

    await provider.send(MESSAGE);

    const [, init] = fetchImplementation.mock.calls[0] ?? [];
    expect(init?.signal).toBeInstanceOf(AbortSignal);
    expect(init?.signal.aborted).toBe(false);
  });

  it.each([400, 401, 429, 500])(
    'maps a %i response to a provider-neutral delivery error',
    async (status) => {
      const provider = new SinchSmsProvider(CONFIG, fetchReturning(status));

      await expect(provider.send(MESSAGE)).rejects.toBeInstanceOf(
        SmsDeliveryError,
      );
    },
  );

  it('maps a transport failure without leaking the message', async () => {
    const provider = new SinchSmsProvider(CONFIG, () =>
      Promise.reject(
        new Error(`connection reset while sending ${MESSAGE.body}`),
      ),
    );

    await expect(provider.send(MESSAGE)).rejects.toThrowError(
      'The SMS provider did not accept the message.',
    );
  });
});
