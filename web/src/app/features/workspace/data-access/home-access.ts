export type HomeView = 'office' | 'field';
/** Permission codes, never role names, decide both navigation and the requested projection. */
export function homeView(
  office: boolean,
  field: boolean,
  requested: string | null,
): HomeView | null {
  if (requested === 'field' && field) return 'field';
  if (office) return 'office';
  return field ? 'field' : null;
}
