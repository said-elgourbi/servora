import { describe, expect, it, vi } from 'vitest';
import { SmsDeliveryError, type OutboundSmsMessage } from '../sms-provider.js';
import {
  TwilioSmsProvider,
  type TwilioClientFactory,
  type TwilioMessageClient,
  type TwilioMessageParams,
} from './twilio-sms-provider.js';

const MESSAGE: OutboundSmsMessage = {
  to: '+15145550100',
  body: 'Servora code 123456',
};

/** Records what the provider handed to Twilio. */
function recordingClient(sent: TwilioMessageParams[]): TwilioMessageClient {
  return {
    sendMessage: (params) => {
      sent.push(params);
      return Promise.resolve({ sid: 'SM00000000000000000000000000000000' });
    },
  };
}

describe('Twilio SMS provider', () => {
  it('sends the composed message from the configured number', async () => {
    const sent: TwilioMessageParams[] = [];
    const provider = new TwilioSmsProvider(
      {
        accountSid: 'AC00000000000000000000000000000000',
        authToken: 'auth-token',
        from: '+15145550999',
      },
      () => recordingClient(sent),
    );

    await provider.send(MESSAGE);

    expect(sent).toEqual([
      {
        to: MESSAGE.to,
        body: MESSAGE.body,
        from: '+15145550999',
      },
    ]);
    // The sender is a number, so Twilio is not asked for a Messaging Service.
    expect(sent[0]).not.toHaveProperty('messagingServiceSid');
  });

  it('sends through the Messaging Service when the sender is a service SID', async () => {
    const sent: TwilioMessageParams[] = [];
    const provider = new TwilioSmsProvider(
      {
        accountSid: 'AC00000000000000000000000000000000',
        authToken: 'auth-token',
        from: 'MG00000000000000000000000000000000',
      },
      () => recordingClient(sent),
    );

    await provider.send(MESSAGE);

    expect(sent).toEqual([
      {
        to: MESSAGE.to,
        body: MESSAGE.body,
        messagingServiceSid: 'MG00000000000000000000000000000000',
      },
    ]);
    // A service SID is not a caller ID, so it never travels as `from`.
    expect(sent[0]).not.toHaveProperty('from');
  });

  it('builds one client and reuses it across messages', async () => {
    const createClient = vi.fn((): TwilioMessageClient => recordingClient([]));
    const provider = new TwilioSmsProvider(
      {
        accountSid: 'AC00000000000000000000000000000000',
        authToken: 'auth-token',
        from: '+15145550999',
      },
      createClient as TwilioClientFactory,
    );

    await provider.send(MESSAGE);
    await provider.send(MESSAGE);

    expect(createClient).toHaveBeenCalledTimes(1);
  });

  it('maps a rejection without leaking the message', async () => {
    const provider = new TwilioSmsProvider(
      {
        accountSid: 'AC00000000000000000000000000000000',
        authToken: 'auth-token',
        from: '+15145550999',
      },
      () => ({
        sendMessage: () =>
          Promise.reject(
            new Error(
              `Invalid parameter: Body must not contain ${MESSAGE.body}`,
            ),
          ),
      }),
    );

    const failure = provider.send(MESSAGE);

    // The provider's own error quotes the message it was given, so nothing of it may surface.
    await expect(failure).rejects.toBeInstanceOf(SmsDeliveryError);
    await expect(failure).rejects.toThrowError(
      'The SMS provider did not accept the message.',
    );
  });
});
