import { mp4AudioBlob } from './field-media';
it('refuses WebM instead of relabeling it as MP4', async () => {
  const source = new Blob([new Uint8Array([0x1a, 0x45, 0xdf, 0xa3])], { type: 'audio/webm' });
  await expect(mp4AudioBlob(source)).rejects.toThrow('UNSUPPORTED_AUDIO');
});
it('refuses claimed MP4 whose bytes do not have an MP4 header', async () => {
  await expect(
    mp4AudioBlob(new Blob(['not an mp4 container'], { type: 'audio/mp4' })),
  ).rejects.toThrow();
});
it('canonicalizes M4A and codec MIME parameters only for proved MP4 bytes', async () => {
  const bytes = new Uint8Array([0, 0, 0, 12, 102, 116, 121, 112, 77, 52, 65, 32]);
  for (const type of ['audio/x-m4a', 'audio/mp4;codecs=mp4a.40.2']) {
    const blob = await mp4AudioBlob(new Blob([bytes], { type }));
    expect(blob.type).toBe('audio/mp4');
    expect(new Uint8Array(await blob.arrayBuffer())).toEqual(bytes);
  }
});
