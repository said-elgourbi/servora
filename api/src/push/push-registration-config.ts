export interface PushRegistrationConfig {
  readonly projectId: string | null;
  readonly applicationId: string | null;
  readonly transportEnabled: boolean;
}

/** Registration stays unavailable until its environment is explicitly selected. */
export function loadPushRegistrationConfig(
  env: NodeJS.ProcessEnv = process.env,
): PushRegistrationConfig {
  const projectId = env.FIREBASE_PROJECT_ID?.trim() || null;
  const applicationId = env.PUSH_ANDROID_APPLICATION_ID?.trim() || null;
  if (projectId && !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId))
    throw new Error('Invalid FIREBASE_PROJECT_ID for push registration.');
  if (
    applicationId &&
    !['com.servora.android', 'com.servora.android.debug'].includes(
      applicationId,
    )
  )
    throw new Error('Invalid PUSH_ANDROID_APPLICATION_ID.');
  if (
    env.NODE_ENV === 'production' &&
    applicationId === 'com.servora.android.debug'
  )
    throw new Error(
      'Production push registration requires the release application ID.',
    );
  return {
    projectId,
    applicationId,
    transportEnabled: env.PUSH_PROVIDER === 'firebase',
  };
}
