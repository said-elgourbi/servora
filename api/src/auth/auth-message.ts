import type { PasswordResetMessage } from '../notifications/password-reset-notifier.js';

// System-generated authentication messages must follow the recipient's language (`BR-028`,
// `Project.md` §10). They live in one place so a message is not defined twice, and a new
// language is a new branch here rather than a change to a flow.
//
// Stored locales are the ones `user_profiles.locale` already holds (`en-CA`, `fr-CA`, …). The
// rule is deliberately narrow: anything that is not French is served in English, which is the
// project's default language.

/** The languages Servora generates authentication messages in. */
export const AUTH_MESSAGE_LOCALES = ['en', 'fr'] as const;
export type AuthMessageLocale = (typeof AUTH_MESSAGE_LOCALES)[number];

/** Resolves the message language from a stored locale, defaulting to English. */
export function resolveAuthMessageLocale(
  locale: string | null | undefined,
): AuthMessageLocale {
  const normalized = (locale ?? '').trim().toLowerCase();
  return normalized.startsWith('fr') ? 'fr' : 'en';
}

/** Subject and body of a password-reset message, including the one-time code. */
export function passwordResetMessage(
  locale: AuthMessageLocale,
  code: string,
  lifetimeMinutes: number,
): Pick<PasswordResetMessage, 'subject' | 'body'> {
  if (locale === 'fr') {
    return {
      subject: 'Réinitialisez votre mot de passe Servora',
      body: [
        `Utilisez ce code pour réinitialiser votre mot de passe Servora : ${code}`,
        '',
        `Il expire dans ${lifetimeMinutes} minutes.`,
        "Si vous n'avez pas demandé cette réinitialisation, ignorez ce message.",
      ].join('\n'),
    };
  }

  return {
    subject: 'Reset your Servora password',
    body: [
      `Use this code to reset your Servora password: ${code}`,
      '',
      `It expires in ${lifetimeMinutes} minutes.`,
      'If you did not request a password reset, ignore this message.',
    ].join('\n'),
  };
}

/** Text of an SMS one-time password, including the code. */
export function smsOtpMessage(
  locale: AuthMessageLocale,
  code: string,
  lifetimeMinutes: number,
): string {
  return locale === 'fr'
    ? `Code de vérification Servora : ${code}. Il expire dans ${lifetimeMinutes} minutes.`
    : `Servora verification code: ${code}. It expires in ${lifetimeMinutes} minutes.`;
}
