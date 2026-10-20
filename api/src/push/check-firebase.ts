import { deleteApp, type App } from 'firebase-admin/app';
import { createFirebasePushApp } from './firebase-client.js';
import { loadPushConfig } from './push-config.js';

/** Checks server credentials without sending to a device or exposing an access token. */
async function main(): Promise<void> {
  const config = loadPushConfig();
  if (config.provider !== 'firebase') {
    throw new Error(
      'Select PUSH_PROVIDER=firebase before checking credentials.',
    );
  }
  let app: App | undefined;
  try {
    app = createFirebasePushApp(config.projectId);
    const credential = app.options.credential;
    if (!credential) throw new Error('Missing server credentials.');
    await credential.getAccessToken();
    console.log(
      'Firebase server credentials acquired an access token. FCM project permissions and device delivery still require a delivery test.',
    );
  } catch {
    // Google errors may contain credential or token material.
    throw new Error(
      'Firebase credential check failed. Configure Application Default Credentials and verify network access; no credentials were logged.',
    );
  } finally {
    if (app) await deleteApp(app);
  }
}

main().catch((error: unknown) => {
  // Only errors created above are printed. SDK failures are redacted inside main.
  console.error(
    error instanceof Error ? error.message : 'Firebase check failed.',
  );
  process.exitCode = 1;
});
