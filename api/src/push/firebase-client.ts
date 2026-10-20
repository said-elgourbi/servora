import {
  applicationDefault,
  initializeApp,
  type App,
} from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';

/** Separate named app so push cannot accidentally inherit another Firebase service's project. */
export function createFirebasePushApp(projectId: string): App {
  return initializeApp(
    {
      projectId,
      credential: applicationDefault(),
    },
    'servora-push',
  );
}

export function firebaseMessaging(app: App) {
  return getMessaging(app);
}
