import { describe, expect, it, vi } from 'vitest';
import { EmailDeliveryError } from '../email-provider.js';
import {
  ResendEmailProvider,
  type FetchLike,
} from './resend-email-provider.js';

const CONFIG = {
  apiKey: 'the-api-key',
  from: 'Servora <no-reply@example.com>',
};

const MESSAGE = {
  to: 'technician@example.com',
  subject: 'Reset your Servora password',
  text: 'Use this code to reset your Servora password: 123456',
};

function fetchReturning(status: number): FetchLike {
  return () => Promise.resolve(new Response('{}', { status }));
}

describe('ResendEmailProvider', () => {
  it('posts the message to the Resend send endpoint', async () => {
    const fetchImplementation = vi.fn(fetchReturning(200));
    const provider = new ResendEmailProvider(CONFIG, fetchImplementation);

    await provider.send(MESSAGE);

    expect(fetchImplementation).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImplementation.mock.calls[0] ?? [];
    expect(url).toBe('https://api.resend.com/emails');
    expect(init?.method).toBe('POST');
    expect(init?.headers['authorization']).toBe('Bearer the-api-key');
    expect(init?.headers['content-type']).toBe('application/json');
    expect(JSON.parse(init?.body ?? '{}')).toEqual({
      from: 'Servora <no-reply@example.com>',
      to: ['technician@example.com'],
      subject: 'Reset your Servora password',
      text: 'Use this code to reset your Servora password: 123456',
    });
  });

  it('bounds the request with a timeout signal', async () => {
    const fetchImplementation = vi.fn(fetchReturning(200));
    const provider = new ResendEmailProvider(CONFIG, fetchImplementation);

    await provider.send(MESSAGE);

    const [, init] = fetchImplementation.mock.calls[0] ?? [];
    expect(init?.signal).toBeInstanceOf(AbortSignal);
    expect(init?.signal.aborted).toBe(false);
  });

  it.each([400, 401, 422, 500])(
    'maps a %i response to a provider-neutral delivery error',
    async (status) => {
      const provider = new ResendEmailProvider(CONFIG, fetchReturning(status));

      await expect(provider.send(MESSAGE)).rejects.toBeInstanceOf(
        EmailDeliveryError,
      );
    },
  );

  it('maps a transport failure without leaking the message', async () => {
    const provider = new ResendEmailProvider(CONFIG, () =>
      Promise.reject(
        new Error(`connection reset while sending ${MESSAGE.text}`),
      ),
    );

    await expect(provider.send(MESSAGE)).rejects.toThrowError(
      'The email provider did not accept the message.',
    );
  });
});
