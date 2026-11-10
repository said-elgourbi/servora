import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { mockJobs, jobPermissions } from './fixtures/jobs';
import { propertySignIn } from './fixtures/properties';
import { job } from './fixtures/workspace';

test('job update composer records phased audio and sends a photo and PDF to the chosen visit', async ({ page }, testInfo) => {
  const visit = {
    id: 'visit-current', sequence: 2, status: 'SCHEDULED', purposeCode: 'WORK', version: 1,
    outcomeCode: null, scheduledStart: '2026-10-30T17:00:00Z', scheduledEnd: '2026-10-30T19:00:00Z', technicians: [],
  };
  await mockJobs(page, [...jobPermissions, 'customers.view', 'evidence.photo.add', 'evidence.audio.add'], {
    ...job, visits: [visit], selectedVisit: null,
  });
  const audioBase64 = (await readFile('e2e/fixtures/field-audio.m4a')).toString('base64');
  await page.addInitScript(({ audioBase64: bytes }) => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: async () => ({ getTracks: () => [{ stop() {} }] }) },
    });
    class FakeMediaRecorder {
      static isTypeSupported(): boolean { return true; }
      state: RecordingState = 'inactive';
      mimeType = 'audio/mp4';
      ondataavailable: ((event: BlobEvent) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      onstop: ((event: Event) => void) | null = null;
      constructor(_stream: MediaStream, _options: MediaRecorderOptions) {}
      start(): void { this.state = 'recording'; }
      stop(): void {
        this.state = 'inactive';
        const raw = atob(bytes);
        const audio = Uint8Array.from(raw, (char) => char.charCodeAt(0));
        this.ondataavailable?.({ data: new Blob([audio], { type: this.mimeType }) } as BlobEvent);
        this.onstop?.(new Event('stop'));
      }
    }
    Object.defineProperty(window, 'MediaRecorder', { configurable: true, value: FakeMediaRecorder });
  }, { audioBase64 });
  const uploads: { path: string; body: string }[] = [];
  for (const kind of ['photos', 'audio-notes', 'documents']) {
    await page.route(`**/jobs/job-one/${kind}`, async (route) => {
      uploads.push({ path: kind, body: route.request().postData() ?? '' });
      return route.fulfill({ status: 201, json: kind === 'documents' ? { id: 'document-one' } : { jobId: job.id, events: [] } });
    });
  }
  await propertySignIn(page, '/work/jobs/job-one?view=office');
  await expect(page.locator('#update-destination')).toHaveValue('visit-current');
  await expect(page.getByRole('button', { name: 'Record audio' })).toBeVisible();
  await page.locator('#update-evidence-phase').selectOption('BEFORE_WORK');
  await page.locator('#update-composer').fill('Arrival details');
  const inputs = page.locator('.composer input[type="file"]');
  await inputs.nth(0).setInputFiles({ name: 'site.png', mimeType: 'image/png', buffer: Buffer.from('png') });
  await inputs.nth(1).setInputFiles({ name: 'report.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF') });
  await page.getByRole('button', { name: 'Record audio' }).click();
  await page.waitForTimeout(1100);
  await page.getByRole('button', { name: 'Stop recording' }).click();
  await expect(page.getByRole('button', { name: 'Audio ready' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath(`job-update-composer-${testInfo.project.name}.png`), fullPage: true });
  await page.getByRole('button', { name: 'Post update' }).click();
  await expect.poll(() => uploads.length).toBe(3);
  expect(uploads.find((upload) => upload.path === 'photos')?.body).toContain('visit-current');
  expect(uploads.find((upload) => upload.path === 'photos')?.body).toContain('clientOperationId');
  expect(uploads.find((upload) => upload.path === 'photos')?.body).toContain('BEFORE_WORK');
  expect(uploads.find((upload) => upload.path === 'audio-notes')?.body).toContain('BEFORE_WORK');
  expect(uploads.find((upload) => upload.path === 'audio-notes')?.body).toContain('audio-note-');
  expect(uploads.find((upload) => upload.path === 'documents')?.body).toContain('clientOperationId');
  expect(uploads.find((upload) => upload.path === 'photos')?.body).toContain('Arrival details');
  expect(uploads.find((upload) => upload.path === 'photos')?.body).toContain('site.png');
  expect(uploads.find((upload) => upload.path === 'documents')?.body).toContain('report.pdf');
});
