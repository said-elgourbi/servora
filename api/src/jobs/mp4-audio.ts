import { AUDIO_CONTENT_TYPES } from '../storage/object-storage.js';
import type { AudioContentType } from '../storage/object-storage.js';

/*
 * What an uploaded audio recording's own bytes say (`BR-015`, `ADR-018` A2/A3).
 *
 * The API accepts one container — `audio/mp4` — and decides that from the bytes rather than from what a
 * client declared, exactly as a photo's type is decided (`ADR-013` D7, `job-photo.dto.ts`). It also
 * reads the recording's **length** out of the container, because `ADR-018` A3 applies the length limit
 * to the recording itself: a duration a client asserts is not a limit, so the value the API stores and
 * bounds is the one the file declares.
 *
 * This is a small, dependency-free ISO-BMFF (MP4) walker rather than a media library: Servora needs two
 * facts from a bounded upload — is this an audio-only MP4, and how long is it — and a parser for those
 * two questions cannot be fooled by a file name or a declared MIME type. A recording whose container
 * cannot be read is refused rather than accepted unverified (`BR-042`).
 */

/** One ISO-BMFF box found in the buffer. */
interface Mp4Box {
  readonly type: string;
  /** The offset of the box's payload, after its size and type (and a large size, where present). */
  readonly payloadStart: number;
  /** The offset one past the box's last byte. */
  readonly end: number;
}

/** The major brands Servora accepts an audio-only MP4 to declare. */
const ACCEPTED_MP4_BRANDS = new Set([
  'M4A ',
  'M4B ',
  'isom',
  'iso2',
  'iso5',
  'mp41',
  'mp42',
]);

/** The handler type an audio track declares inside `mdia/hdlr`. */
const AUDIO_HANDLER = 'soun';

/** The handler type a video track declares; a file carrying one is not audio evidence. */
const VIDEO_HANDLER = 'vide';

/**
 * The boxes in `[start, end)`, or `null` when the buffer is not well-formed ISO-BMFF.
 *
 * A box is a 32-bit size and a four-character type, with two special sizes: `1` means the real size is a
 * 64-bit value after the type, and `0` means the box runs to the end of its parent. Anything else that
 * does not fit inside its parent makes the file unreadable, which is a refusal rather than a guess.
 */
function readBoxes(bytes: Buffer, start: number, end: number): Mp4Box[] | null {
  const boxes: Mp4Box[] = [];
  let offset = start;
  while (offset + 8 <= end) {
    const declaredSize = bytes.readUInt32BE(offset);
    const type = bytes.toString('latin1', offset + 4, offset + 8);
    let size = declaredSize;
    let payloadStart = offset + 8;
    if (declaredSize === 1) {
      if (offset + 16 > end) {
        return null;
      }
      const large = bytes.readBigUInt64BE(offset + 8);
      if (large > BigInt(Number.MAX_SAFE_INTEGER)) {
        return null;
      }
      size = Number(large);
      payloadStart = offset + 16;
    } else if (declaredSize === 0) {
      size = end - offset;
    }
    if (size < payloadStart - offset || offset + size > end) {
      return null;
    }
    boxes.push({ type, payloadStart, end: offset + size });
    offset += size;
  }
  return boxes;
}

/** The first box of [type] in a list, or `undefined`. */
function findBox(boxes: readonly Mp4Box[], type: string): Mp4Box | undefined {
  return boxes.find((box) => box.type === type);
}

/** The `ftyp` the buffer starts with, as its major brand, or `null`. */
function readMajorBrand(bytes: Buffer): string | null {
  const [first] = readBoxes(bytes, 0, bytes.length) ?? [];
  if (first === undefined || first.type !== 'ftyp' || first.payloadStart + 8 > first.end) {
    return null;
  }
  return bytes.toString('latin1', first.payloadStart, first.payloadStart + 4);
}

/** The handler types the `moov` declares, read from each `trak/mdia/hdlr`. */
function readTrackHandlers(bytes: Buffer, moov: Mp4Box): string[] {
  const handlers: string[] = [];
  for (const trak of readBoxes(bytes, moov.payloadStart, moov.end) ?? []) {
    if (trak.type !== 'trak') {
      continue;
    }
    const mdia = findBox(readBoxes(bytes, trak.payloadStart, trak.end) ?? [], 'mdia');
    if (mdia === undefined) {
      continue;
    }
    const hdlr = findBox(readBoxes(bytes, mdia.payloadStart, mdia.end) ?? [], 'hdlr');
    if (hdlr === undefined || hdlr.payloadStart + 12 > hdlr.end) {
      continue;
    }
    // A full box's version and flags take four bytes, then `pre_defined` four, then the handler type.
    handlers.push(bytes.toString('latin1', hdlr.payloadStart + 8, hdlr.payloadStart + 12));
  }
  return handlers;
}

/**
 * The recording's length in seconds, read from the `moov`'s `mvhd`, or `null` when it cannot be read.
 *
 * `mvhd` carries a timescale and a duration in that timescale, so the length is their quotient. Version
 * 1 widens both timestamps to 64 bits, which is the only difference the two layouts have for this read.
 * A zero timescale, a zero duration or a missing `mvhd` (a fragmented file) is a refusal: the API will
 * not apply a length limit to a length it could not read.
 */
export function readMp4AudioDurationSeconds(bytes: Buffer): number | null {
  const moov = findBox(readBoxes(bytes, 0, bytes.length) ?? [], 'moov');
  if (moov === undefined) {
    return null;
  }
  const mvhd = findBox(readBoxes(bytes, moov.payloadStart, moov.end) ?? [], 'mvhd');
  if (mvhd === undefined || mvhd.payloadStart + 4 > mvhd.end) {
    return null;
  }

  const version = bytes[mvhd.payloadStart];
  let timescale: number;
  let duration: number;
  if (version === 1) {
    if (mvhd.payloadStart + 32 > mvhd.end) {
      return null;
    }
    timescale = bytes.readUInt32BE(mvhd.payloadStart + 20);
    const large = bytes.readBigUInt64BE(mvhd.payloadStart + 24);
    if (large > BigInt(Number.MAX_SAFE_INTEGER)) {
      return null;
    }
    duration = Number(large);
  } else if (version === 0) {
    if (mvhd.payloadStart + 20 > mvhd.end) {
      return null;
    }
    timescale = bytes.readUInt32BE(mvhd.payloadStart + 12);
    duration = bytes.readUInt32BE(mvhd.payloadStart + 16);
  } else {
    return null;
  }

  if (timescale <= 0 || duration <= 0) {
    return null;
  }
  return duration / timescale;
}

/**
 * The content type the bytes declare, or `null` when they are not the container Servora accepts.
 *
 * The file must start with an accepted `ftyp` brand, must carry a `moov` with a length, and must declare
 * an audio track and **no** video track: `audio/mp4` is audio evidence, so an MP4 that carries video is
 * refused rather than accepted as an audio note (`ADR-018` A2).
 */
export function sniffAudioContentType(bytes: Buffer): AudioContentType | null {
  const brand = readMajorBrand(bytes);
  if (brand === null || !ACCEPTED_MP4_BRANDS.has(brand)) {
    return null;
  }
  const moov = findBox(readBoxes(bytes, 0, bytes.length) ?? [], 'moov');
  if (moov === undefined) {
    return null;
  }
  const handlers = readTrackHandlers(bytes, moov);
  if (!handlers.includes(AUDIO_HANDLER) || handlers.includes(VIDEO_HANDLER)) {
    return null;
  }
  if (readMp4AudioDurationSeconds(bytes) === null) {
    return null;
  }
  return 'audio/mp4';
}

/** The one content type this module answers with, so a caller need not repeat the literal. */
export const MP4_AUDIO_CONTENT_TYPE: AudioContentType = 'audio/mp4';

/** The extension the accepted container is stored under (`AUDIO_CONTENT_TYPES`). */
export const MP4_AUDIO_EXTENSION = AUDIO_CONTENT_TYPES[MP4_AUDIO_CONTENT_TYPE];
