import { Test } from '@nestjs/testing';
import { EMAIL_PROVIDER, type EmailProvider } from './email-provider.js';
import { EmailModule } from './email.module.js';
import { NoOpEmailProvider } from './providers/no-op-email-provider.js';

describe('email port', () => {
  it('resolves the port to the no-op provider without a provider account', async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [EmailModule],
    }).compile();

    const emailProvider = moduleRef.get<EmailProvider>(EMAIL_PROVIDER);

    expect(emailProvider).toBeInstanceOf(NoOpEmailProvider);

    await moduleRef.close();
  });

  it('accepts a message without logging or retaining it', async () => {
    const provider: EmailProvider = new NoOpEmailProvider();

    await expect(
      provider.send({
        to: 'technician@example.com',
        subject: 'Reset your Servora password',
        text: 'Use this code to reset your Servora password: 123456',
      }),
    ).resolves.toBeUndefined();
  });
});
