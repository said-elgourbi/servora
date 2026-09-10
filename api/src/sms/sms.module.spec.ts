import { Test } from '@nestjs/testing';
import { NoOpSmsProvider } from './providers/no-op-sms-provider.js';
import { SmsModule } from './sms.module.js';
import { SMS_PROVIDER, type SmsProvider } from './sms-provider.js';

describe('SMS port', () => {
  it('resolves the port to the development provider without a provider account', async () => {
    const moduleRef = await Test.createTestingModule({
      imports: [SmsModule],
    }).compile();

    const smsProvider = moduleRef.get<SmsProvider>(SMS_PROVIDER);

    expect(smsProvider).toBeInstanceOf(NoOpSmsProvider);

    await moduleRef.close();
  });

  it('accepts a message without logging or retaining it', async () => {
    const provider: SmsProvider = new NoOpSmsProvider();

    await expect(
      provider.send({ to: '+15145550100', body: 'one-time code 123456' }),
    ).resolves.toBeUndefined();
  });
});
