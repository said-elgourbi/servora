/** Storage can be unavailable in private/restricted browsers. Preferences still work in memory. */
export function readPreference(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
export function writePreference(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* Non-secret preferences are optional. */
  }
}
