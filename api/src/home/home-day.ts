/**
 * The local day a home read is rendered for.
 *
 * "Today" is a local-calendar question, so the screen cannot answer it alone and the client cannot
 * be trusted to answer it authoritatively: a device whose clock or zone is wrong would report a
 * different day's work than the operation actually has (`BR-001`, `BR-042`).
 *
 * The client therefore names the zone it renders in and the API settles the window, so two clients
 * of the same operation asked at the same moment agree on which Visits are today's.
 *
 * Nothing here is business state: it is a calendar window derived from a zone and an instant. Both
 * home reads resolve their day through it — `GET /home/manager` and `GET /home/technician` — so the
 * two screens cannot disagree about which day they describe (`BR-041`).
 */

/** The half-open instant window `[start, end)` one local calendar day covers. */
export interface DayWindow {
  /** The IANA time-zone identifier the window was resolved in. */
  readonly timeZone: string;
  /** The instant the local day begins. */
  readonly start: Date;
  /** The instant the next local day begins. */
  readonly end: Date;
}

/** The zone a request that names no zone is resolved in. */
export const DEFAULT_HOME_TIME_ZONE = 'UTC';

/**
 * The longest zone identifier the API accepts.
 *
 * The longest IANA identifier in use is well under this; the bound exists so a request cannot make
 * the API allocate on an unbounded string (`dev.md` §7).
 */
const MAX_TIME_ZONE_LENGTH = 64;

const formatters = new Map<string, Intl.DateTimeFormat>();

/**
 * Whether [timeZone] is a zone this runtime can format instants in.
 *
 * The check formats a known instant rather than comparing against a fixed identifier list, so an
 * accepted alias and a fixed-offset identifier behave the same way as a canonical name.
 */
function isSupportedTimeZone(timeZone: string): boolean {
  try {
    formatterFor(timeZone).format(new Date(0));
    return true;
  } catch {
    return false;
  }
}

/**
 * A device-reported fixed offset, in the forms a platform hands out.
 *
 * Android reports `GMT+05:30` for an offset with no named zone; this runtime only format-instants a
 * bare `+05:30`, so the prefix is removed rather than the identifier being refused. A device in such
 * a zone must still be able to open a home screen for its own day.
 */
const DEVICE_FIXED_OFFSET = /^(?:GMT|UTC)?([+-])(\d{2}):?(\d{2})$/i;

/**
 * The zone identifier [value] names, or `null` when it names one this runtime cannot resolve.
 *
 * The returned value is the identifier the day window is resolved in, so what the response echoes
 * back is what was actually used rather than what was submitted.
 */
export function normaliseTimeZone(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_TIME_ZONE_LENGTH) {
    return null;
  }

  const offset = DEVICE_FIXED_OFFSET.exec(trimmed);
  if (offset !== null) {
    const candidate = `${offset[1]}${offset[2]}:${offset[3]}`;
    return isSupportedTimeZone(candidate) ? candidate : null;
  }
  return isSupportedTimeZone(trimmed) ? trimmed : null;
}

/**
 * One local calendar date, as a read names it (`BR-072`).
 *
 * A date is a calendar fact rather than an instant, so a client that lets the user pick a day from a
 * month or a week strip has to name it as one. The API resolves the day's instants from it, so the
 * window never depends on the device's own idea of a day's offset (`BR-001`).
 */
export interface LocalDate {
  readonly year: number;
  /** 1-12, as a person writes it — not the 0-based month a JavaScript `Date` carries. */
  readonly month: number;
  readonly day: number;
}

/** The local calendar date [instant] falls on in [timeZone]. */
export function localDateIn(timeZone: string, instant: Date): LocalDate {
  const local = localWallClock(timeZone, instant);
  return {
    year: local.getUTCFullYear(),
    month: local.getUTCMonth() + 1,
    day: local.getUTCDate(),
  };
}

/** The local calendar date [date] names, in the `YYYY-MM-DD` form a client sends and a read echoes. */
export function formatLocalDate(date: LocalDate): string {
  const month = date.month.toString().padStart(2, '0');
  const day = date.day.toString().padStart(2, '0');
  return `${date.year.toString().padStart(4, '0')}-${month}-${day}`;
}

/**
 * Resolves the local calendar date that contains [now] in [timeZone].
 *
 * It is expressed over [resolveLocalDayWindow] so the day a home resolves from the clock and the day
 * a schedule resolves from a chosen date are resolved by one implementation, and the two cannot
 * disagree about where a local day begins (`BR-041`).
 */
export function resolveDayWindow(timeZone: string, now: Date): DayWindow {
  return resolveLocalDayWindow(timeZone, localDateIn(timeZone, now));
}

/**
 * The half-open instant window the local calendar date [date] covers in [timeZone].
 *
 * The offset is taken twice: a first pass lands inside the local day and the second pass corrects
 * for a zone whose offset changed between the instant being asked about and that day's midnight, so
 * a day that starts on a daylight-saving boundary still starts at 00:00 local.
 */
export function resolveLocalDayWindow(
  timeZone: string,
  date: LocalDate,
): DayWindow {
  return {
    timeZone,
    start: instantOfLocalMidnight(timeZone, Date.UTC(date.year, date.month - 1, date.day)),
    end: instantOfLocalMidnight(
      timeZone,
      Date.UTC(date.year, date.month - 1, date.day + 1),
    ),
  };
}

/**
 * The instant a local midnight (expressed as a wall-clock UTC timestamp) begins.
 *
 * The wall clock itself is the first pass's reference instant: it is at most a day away from the
 * midnight being resolved, so the correction pass lands on the offset that midnight really has.
 */
function instantOfLocalMidnight(
  timeZone: string,
  wallClockMidnight: number,
): Date {
  const atWallClock = new Date(wallClockMidnight);
  const first = new Date(wallClockMidnight - offsetAt(timeZone, atWallClock));
  return new Date(wallClockMidnight - offsetAt(timeZone, first));
}

/** The wall clock [timeZone] shows at [instant], expressed as a UTC timestamp. */
function localWallClock(timeZone: string, instant: Date): Date {
  const parts = formatterFor(timeZone).formatToParts(instant);
  return new Date(
    Date.UTC(
      partValue(parts, 'year'),
      partValue(parts, 'month') - 1,
      partValue(parts, 'day'),
      partValue(parts, 'hour'),
      partValue(parts, 'minute'),
      partValue(parts, 'second'),
    ),
  );
}

/** How far ahead of UTC [timeZone] is at [instant], in milliseconds. */
function offsetAt(timeZone: string, instant: Date): number {
  // The formatter reports whole seconds, so the instant is aligned to a whole second before the
  // difference is taken. Without that, the fraction of the second would leak into the offset and
  // move the resolved midnight by the same fraction — a window that changes with the millisecond the
  // request happened to arrive.
  const aligned = Math.floor(instant.getTime() / 1000) * 1000;
  return localWallClock(timeZone, new Date(aligned)).getTime() - aligned;
}

function partValue(parts: Intl.DateTimeFormatPart[], type: string): number {
  const part = parts.find((candidate) => candidate.type === type);
  return part === undefined ? 0 : Number.parseInt(part.value, 10);
}

/**
 * The formatter for [timeZone], reused between the three reads one window needs.
 *
 * `hourCycle: 'h23'` is asked for explicitly: with a 12-hour cycle, or with `hour12: false` on some
 * locales, midnight is reported as hour 24 of the previous day, which would put the window a day
 * out.
 */
function formatterFor(timeZone: string): Intl.DateTimeFormat {
  const existing = formatters.get(timeZone);
  if (existing !== undefined) {
    return existing;
  }
  const created = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  formatters.set(timeZone, created);
  return created;
}
