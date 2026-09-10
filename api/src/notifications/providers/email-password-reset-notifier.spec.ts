import { describe, expect, it } from 'vitest';
import { EmailDeliveryError } from '../../email/email-provider.js';
import { FakeEmailProvider } from '../../email/providers/fake-email-provider.js';
import { EmailPasswordResetNotifier } from './email-password-reset-notifier.js';

const MESSAGE = {
  to: 'technician@example.com',
  code: '123456',
  subject: 'Reset your Servora password',
  body: 'Use this code to reset your Servora password: 123456',
};

describe('EmailPasswordResetNotifier', () => {
  it('hands the composed message to the email port unchanged', async () => {
    const emailProvider = new FakeEmailProvider();
    const notifier = new EmailPasswordResetNotifier(emailProvider);

    await notifier.send(MESSAGE);

    expect(emailProvider.sent).toEqual([
      {
        to: 'technician@example.com',
        subject: 'Reset your Servora password',
        text: 'Use this code to reset your Servora password: 123456',
      },
    ]);
  });

  it('does not swallow a delivery failure: the caller decides how to report it', async () => {
    const emailProvider = new FakeEmailProvider();
    emailProvider.failNextSend(new EmailDeliveryError());
    const notifier = new EmailPasswordResetNotifier(emailProvider);

    await expect(notifier.send(MESSAGE)).rejects.toBeInstanceOf(
      EmailDeliveryError,
    );
  });
});
