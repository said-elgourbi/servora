import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import type {
  PasswordResetMessage,
  PasswordResetNotifier,
} from '../password-reset-notifier.js';

/**
 * Development and manual-QA binding of `PasswordResetNotifier`: it writes each message to a
 * file instead of sending it.
 *
 * Why it exists: the reset flow is unusable end-to-end before a production transport is
 * approved (`ADR-006` D3), and a developer or the product owner has to be able to read the
 * code to exercise the flow on a real device. `loadNotificationsConfig` refuses this
 * transport under `NODE_ENV=production`, so it cannot become a production delivery path.
 *
 * The file name hashes the recipient rather than naming it, so the directory listing does not
 * disclose which accounts requested a reset. Nothing is logged.
 */
export class FilePasswordResetNotifier implements PasswordResetNotifier {
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = isAbsolute(directory)
      ? directory
      : resolve(process.cwd(), directory);
  }

  async send(message: PasswordResetMessage): Promise<void> {
    await mkdir(this.directory, { recursive: true });

    const recipientKey = createHash('sha256')
      .update(message.to, 'utf8')
      .digest('hex')
      .slice(0, 12);
    const fileName = `${Date.now()}-${recipientKey}.txt`;
    const contents = [
      `to: ${message.to}`,
      `subject: ${message.subject}`,
      '',
      message.body,
      '',
    ].join('\n');

    await writeFile(join(this.directory, fileName), contents, {
      encoding: 'utf8',
      // Only the owner may read a file that holds a one-time code.
      mode: 0o600,
    });
  }
}
