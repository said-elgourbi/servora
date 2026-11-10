export function avatarInitials(name: string | null | undefined): string {
  const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
  return parts.slice(0, 2).map((part) => part[0] ?? '').join('').toLocaleUpperCase();
}

/** Stable per-person hue so the same initials keep the same fallback color across the page. */
export function avatarHue(name: string | null | undefined): string {
  let hash = 0;
  for (const char of (name ?? '').trim().toLocaleLowerCase()) hash = (hash * 31 + char.codePointAt(0)!) >>> 0;
  return `${hash % 360}deg`;
}
