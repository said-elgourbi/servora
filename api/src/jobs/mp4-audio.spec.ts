import { readMp4AudioDurationSeconds, sniffAudioContentType } from './mp4-audio.js';

/*
 * What an uploaded recording's own bytes say (`BR-015`, `ADR-018` A2/A3).
 *
 * The fixtures below are built the way a container is: a box is a 32-bit size, a four-character type and
 * its payload. That is deliberate — the rule under test is "the API reads the container", so the tests
 * must hand it a container rather than a stub that would pass for any parser.
 */

/** One ISO-BMFF box: its size, its type and its payload. */
function box(type: string, payload: Buffer): Buffer {
  const header = Buffer.alloc(8);
  header.writeUInt32BE(payload.length + 8, 0);
  header.write(type, 4, 'latin1');
  return Buffer.concat([header, payload]);
}

/** An `hdlr` box declaring one track's handler type. */
function hdlr(handler: string): Buffer {
  const payload = Buffer.alloc(24);
  payload.write(handler, 8, 'latin1');
  return box('hdlr', payload);
}

/** An `mvhd` box carrying a timescale and a duration in it. */
function mvhd(timescale: number, duration: number, version: 0 | 1): Buffer {
  if (version === 1) {
    const payload = Buffer.alloc(112);
    payload[0] = 1;
    payload.writeUInt32BE(timescale, 20);
    payload.writeBigUInt64BE(BigInt(duration), 24);
    return box('mvhd', payload);
  }
  const payload = Buffer.alloc(100);
  payload.writeUInt32BE(timescale, 12);
  payload.writeUInt32BE(duration, 16);
  return box('mvhd', payload);
}

/** An MP4 with the brands, the tracks and the length a test asks for. */
function audioMp4(
  options: {
    seconds?: number;
    brand?: string;
    handlers?: readonly string[];
    timescale?: number;
    version?: 0 | 1;
    includeMoov?: boolean;
  } = {},
): Buffer {
  const timescale = options.timescale ?? 1000;
  const seconds = options.seconds ?? 18;
  const ftyp = box(
    'ftyp',
    Buffer.concat([
      Buffer.from(options.brand ?? 'isom', 'latin1'),
      Buffer.from([0x00, 0x00, 0x02, 0x00]),
      Buffer.from('isommp42', 'latin1'),
    ]),
  );
  if (options.includeMoov === false) {
    return ftyp;
  }
  const handlers = options.handlers ?? ['soun'];
  const moov = box(
    'moov',
    Buffer.concat([
      mvhd(timescale, Math.round(seconds * timescale), options.version ?? 0),
      ...handlers.map((handler) => box('trak', box('mdia', hdlr(handler)))),
    ]),
  );
  return Buffer.concat([ftyp, moov]);
}

describe('an uploaded recording', () => {
  it('is accepted as audio/mp4 when it is an audio-only MP4 with a length', () => {
    expect(sniffAudioContentType(audioMp4())).toBe('audio/mp4');
    expect(sniffAudioContentType(audioMp4({ brand: 'M4A ' }))).toBe('audio/mp4');
    expect(sniffAudioContentType(audioMp4({ brand: 'mp42' }))).toBe('audio/mp4');
  });

  it('declares its own length, which is what the API bounds', () => {
    expect(readMp4AudioDurationSeconds(audioMp4({ seconds: 18 }))).toBe(18);
    expect(readMp4AudioDurationSeconds(audioMp4({ seconds: 1 }))).toBe(1);
    expect(
      readMp4AudioDurationSeconds(audioMp4({ seconds: 301, timescale: 44100 })),
    ).toBeCloseTo(301, 3);
    // Version 1 widens the duration to 64 bits; the length still reads the same way.
    expect(readMp4AudioDurationSeconds(audioMp4({ seconds: 42, version: 1 }))).toBe(42);
  });

  it('is refused when it carries video, because audio evidence is audio', () => {
    expect(sniffAudioContentType(audioMp4({ handlers: ['soun', 'vide'] }))).toBeNull();
    expect(sniffAudioContentType(audioMp4({ handlers: ['vide'] }))).toBeNull();
  });

  it('is refused when its container is not an MP4 Servora accepts', () => {
    // A JPEG, a QuickTime brand, and an MP4 with no `moov` at all.
    expect(sniffAudioContentType(Buffer.from([0xff, 0xd8, 0xff, 0xe0]))).toBeNull();
    expect(sniffAudioContentType(Buffer.from('not a recording'))).toBeNull();
    expect(sniffAudioContentType(audioMp4({ brand: 'qt  ' }))).toBeNull();
    expect(sniffAudioContentType(audioMp4({ includeMoov: false }))).toBeNull();
  });

  it('is refused when a length cannot be read from it', () => {
    // A zero duration, a zero timescale, and a `moov` whose `mvhd` is missing.
    expect(
      readMp4AudioDurationSeconds(
        Buffer.concat([
          box('ftyp', Buffer.alloc(16)),
          box('moov', box('trak', box('mdia', hdlr('soun')))),
        ]),
      ),
    ).toBeNull();
    expect(readMp4AudioDurationSeconds(audioMp4({ timescale: 0, seconds: 5 }))).toBeNull();
    expect(readMp4AudioDurationSeconds(audioMp4({ seconds: 0 }))).toBeNull();
    expect(sniffAudioContentType(audioMp4({ timescale: 0, seconds: 5 }))).toBeNull();
  });

  it('is refused when its box structure does not hold together', () => {
    const truncated = audioMp4().subarray(0, 20);
    expect(sniffAudioContentType(truncated)).toBeNull();
    expect(readMp4AudioDurationSeconds(truncated)).toBeNull();

    // A declared size larger than the buffer is not a container the API will read a length from.
    const liar = Buffer.concat([
      audioMp4(),
      Buffer.from([0x00, 0x00, 0x00, 0x40, 0x66, 0x72, 0x65, 0x65]),
    ]);
    expect(sniffAudioContentType(liar)).toBeNull();
  });

  it('reads the length from a box whose size is the 64-bit form', () => {
    const ftyp64 = Buffer.concat([
      Buffer.from([0x00, 0x00, 0x00, 0x01]),
      Buffer.from('ftyp', 'latin1'),
      // The 64-bit size that follows the type, then the payload it counts: brand and minor version.
      Buffer.from([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x18]),
      Buffer.from('isom', 'latin1'),
      Buffer.from([0x00, 0x00, 0x02, 0x00]),
    ]);
    const container = Buffer.concat([
      ftyp64,
      box('moov', mvhd(1000, 12_000, 0)),
      box('trak', Buffer.alloc(0)),
    ]);
    expect(readMp4AudioDurationSeconds(container)).toBe(12);
  });
});
