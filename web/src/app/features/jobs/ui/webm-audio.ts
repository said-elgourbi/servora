function readVint(bytes: Uint8Array, offset: number) {
  const first = bytes[offset];
  if (first === undefined || first === 0) return null;
  let marker = 0x80;
  let width = 1;
  while ((first & marker) === 0 && width <= 8) { marker >>= 1; width++; }
  if (width > 8 || offset + width > bytes.length) return null;
  let value = first & (marker - 1);
  for (let i = 1; i < width; i++) value = value * 256 + bytes[offset + i]!;
  return { width, value, unknown: value === 2 ** (7 * width) - 1 };
}

function encodeVint(value: number): Uint8Array {
  for (let width = 1; width <= 8; width++) {
    if (value > 2 ** (7 * width) - 2) continue;
    const bytes = new Uint8Array(width);
    let remaining = value;
    for (let i = width - 1; i >= 0; i--) { bytes[i] = remaining & 0xff; remaining = Math.floor(remaining / 256); }
    bytes[0] = bytes[0]! | (1 << (8 - width));
    return bytes;
  }
  throw new Error('INVALID_WEBM_SIZE');
}

function sizeOf(value: number): number { return encodeVint(value).length; }

/** Add the duration Chrome omits from live WebM recordings, using measured recorder time. */
export async function webmWithDuration(source: Blob, durationMs: number): Promise<Blob> {
  const bytes = new Uint8Array(await source.arrayBuffer());
  const infoId = new Uint8Array([0x15, 0x49, 0xa9, 0x66]);
  const infoOffset = bytes.findIndex((_, index) => index + 4 <= bytes.length &&
    bytes[index] === infoId[0] && bytes[index + 1] === infoId[1] && bytes[index + 2] === infoId[2] && bytes[index + 3] === infoId[3]);
  if (infoOffset < 0) throw new Error('INVALID_WEBM_INFO');
  const infoSize = readVint(bytes, infoOffset + 4);
  if (!infoSize || infoSize.unknown) throw new Error('INVALID_WEBM_INFO');
  const infoDataStart = infoOffset + 4 + infoSize.width;
  const infoEnd = infoDataStart + infoSize.value;
  if (infoEnd > bytes.length) throw new Error('INVALID_WEBM_INFO');
  if (bytes.slice(infoDataStart, infoEnd - 1).some((value, index) => value === 0x44 && bytes[infoDataStart + index + 1] === 0x89)) return source;

  let timecodeScale = 1_000_000;
  const scaleId = new Uint8Array([0x2a, 0xd7, 0xb1]);
  const scaleOffset = bytes.findIndex((_, index) => index + 3 <= bytes.length && bytes[index] === scaleId[0] && bytes[index + 1] === scaleId[1] && bytes[index + 2] === scaleId[2]);
  if (scaleOffset >= infoDataStart && scaleOffset < infoEnd) {
    const scaleSize = readVint(bytes, scaleOffset + 3);
    if (!scaleSize || scaleSize.unknown || scaleSize.value < 1 || scaleSize.value > 8 || scaleOffset + 3 + scaleSize.width + scaleSize.value > infoEnd)
      throw new Error('INVALID_WEBM_SCALE');
    timecodeScale = 0;
    const start = scaleOffset + 3 + scaleSize.width;
    for (let i = 0; i < scaleSize.value; i++) timecodeScale = timecodeScale * 256 + bytes[start + i]!;
  }
  const duration = new ArrayBuffer(8);
  new DataView(duration).setFloat64(0, durationMs * 1_000_000 / timecodeScale, false);
  const durationElement = new Uint8Array(11);
  durationElement.set([0x44, 0x89, 0x88]);
  durationElement.set(new Uint8Array(duration), 3);
  const infoContent = new Uint8Array(4 + sizeOf(infoSize.value + durationElement.length) + infoSize.value + durationElement.length);
  infoContent.set(bytes.slice(infoOffset, infoOffset + 4));
  const encodedInfoSize = encodeVint(infoSize.value + durationElement.length);
  infoContent.set(encodedInfoSize, 4);
  infoContent.set(bytes.slice(infoDataStart, infoEnd), 4 + encodedInfoSize.length);
  infoContent.set(durationElement, 4 + encodedInfoSize.length + infoSize.value);

  const segmentId = new Uint8Array([0x18, 0x53, 0x80, 0x67]);
  const segmentOffset = bytes.findIndex((_, index) => index + 4 <= infoOffset && bytes[index] === segmentId[0] && bytes[index + 1] === segmentId[1] && bytes[index + 2] === segmentId[2] && bytes[index + 3] === segmentId[3]);
  if (segmentOffset < 0) throw new Error('INVALID_WEBM_SEGMENT');
  const segmentSize = readVint(bytes, segmentOffset + 4);
  if (!segmentSize) throw new Error('INVALID_WEBM_SEGMENT');
  const segmentDataStart = segmentOffset + 4 + segmentSize.width;
  const rewrittenSegmentSize = segmentSize.unknown ? bytes.slice(segmentOffset + 4, segmentDataStart) : encodeVint(segmentSize.value + infoContent.length - (infoEnd - infoOffset));
  const output = new Uint8Array(segmentOffset + 4 + rewrittenSegmentSize.length + (infoOffset - segmentDataStart) + infoContent.length + bytes.length - infoEnd);
  output.set(bytes.slice(0, segmentOffset + 4));
  output.set(rewrittenSegmentSize, segmentOffset + 4);
  output.set(bytes.slice(segmentDataStart, infoOffset), segmentOffset + 4 + rewrittenSegmentSize.length);
  output.set(infoContent, segmentOffset + 4 + rewrittenSegmentSize.length + infoOffset - segmentDataStart);
  output.set(bytes.slice(infoEnd), segmentOffset + 4 + rewrittenSegmentSize.length + infoOffset - segmentDataStart + infoContent.length);
  return new Blob([output], { type: 'audio/webm' });
}
