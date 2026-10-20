/** Canonicalize MIME aliases only after proving the bytes are an MP4 container. */
export async function mp4AudioBlob(source: Blob): Promise<Blob> {
  const declared = source.type.split(';')[0]?.toLowerCase();
  if (!['audio/mp4', 'audio/x-m4a'].includes(declared ?? '')) throw new Error('UNSUPPORTED_AUDIO');
  const header = new Uint8Array(await source.slice(0, 12).arrayBuffer());
  if (header.length < 12 || String.fromCharCode(...header.slice(4, 8)) !== 'ftyp')
    throw new Error('UNSUPPORTED_AUDIO');
  return new Blob([source], { type: 'audio/mp4' });
}
