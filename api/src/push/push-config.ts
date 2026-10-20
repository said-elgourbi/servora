export type PushConfig =
  | { readonly provider: 'disabled' }
  | { readonly provider: 'firebase'; readonly projectId: string };

/** Credentials use Google's Application Default Credentials chain, never Android's API key. */
export function loadPushConfig(
  env: NodeJS.ProcessEnv = process.env,
): PushConfig {
  const provider = env.PUSH_PROVIDER?.trim() || 'disabled';
  if (provider === 'disabled') return { provider };
  if (provider !== 'firebase') {
    throw new Error('PUSH_PROVIDER must be disabled or firebase.');
  }
  const projectId = env.FIREBASE_PROJECT_ID?.trim();
  if (!projectId || !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)) {
    throw new Error(
      'FIREBASE_PROJECT_ID must be a valid Google Cloud project ID when PUSH_PROVIDER=firebase.',
    );
  }
  return { provider, projectId };
}
