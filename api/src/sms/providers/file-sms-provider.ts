import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import type { OutboundSmsMessage, SmsProvider } from '../sms-provider.js';

/**
 * Development and manual-QA binding of `SmsProvider`: it writes each message to a file instead of
 * sending it.
 *
 * Why it exists: without a provider account no one-time password reaches a handset, so the phone
 * sign-in flow could not be exercised on a real device at all. `loadSmsConfig` refuses this
 * provider under `NODE_ENV=production`, so it cannot become a delivery path in a deployment
 * (`dev.md` §11, `ADR-006` D4).
 *
 * The file name hashes the recipient rather than naming it, and nothing is logged: a message body
 * carries the code (`BR-046`).
 */
export class FileSmsProvider implements SmsProvider {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = isAbsolute(directory)
      ? directory
      : resolve(process.cwd(), directory);
  }

  async send(message: OutboundSmsMessage): Promise<void> {
    await mkdir(this.directory, { recursive: true });

    const recipientKey = createHash('sha256')
      .update(message.to, 'utf8')
      .digest('hex')
      .slice(0, 12);
    const fileName = `${Date.now()}-sms-${recipientKey}.txt`;

    await writeFile(
      join(this.directory, fileName),
      `to: ${message.to}\n\n${message.body}\n`,
      // Only the owner may read a file that holds a one-time code.
      { encoding: 'utf8', mode: 0o600 },
    );
  }
}
