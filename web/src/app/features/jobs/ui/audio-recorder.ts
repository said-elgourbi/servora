import { ChangeDetectionStrategy, Component, OnDestroy, effect, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { I18n } from '../../../core/i18n/i18n';
import { Icon } from '../../../shared/ui/icon';
import type { TranslationKey } from '../../../core/i18n/en';
import { webmWithDuration } from './webm-audio';

const maxBytes = 10 * 1024 * 1024;
const maxDurationMs = 299_000;

@Component({
  selector: 'app-audio-recorder',
  imports: [MatButtonModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (recording()) {
      <span class="recording-indicator" role="status"><span class="pulse"></span>{{ i18n.text('jobAudioRecording') }}</span>
      <button mat-stroked-button type="button" [disabled]="disabled()" (click)="stop()"><app-icon name="audio" />{{ i18n.text('jobAudioStopRecording') }}</button>
    } @else if (previewUrl()) {
      <audio controls [src]="previewUrl()" [attr.aria-label]="i18n.text('jobAudioPreview')"></audio>
      <button mat-stroked-button type="button" [disabled]="disabled() || starting()" (click)="start()">{{ i18n.text('jobAudioRecordAgain') }}</button>
      <button mat-button type="button" [disabled]="disabled()" (click)="discard()">{{ i18n.text('jobAudioDiscard') }}</button>
    } @else {
      <button mat-stroked-button type="button" [disabled]="disabled() || starting()" (click)="start()"><app-icon name="audio" />{{ i18n.text('jobAudioRecord') }}</button>
    }
    @if (error(); as key) { <p class="error" role="alert">{{ i18n.text(key) }}</p> }
  `,
  styles: `
    :host { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
    button { min-height: 36px; max-width: 100%; }
    audio { width: min(320px, 100%); height: 40px; }
    app-icon { width: 15px; height: 15px; margin-right: 6px; }
    .error { flex-basis: 100%; margin: 0; color: var(--app-danger-text); font-size: 12px; }
    .recording-indicator { display:inline-flex; align-items:center; gap:8px; color:var(--app-danger-text); font-size:13px; font-weight:600; }
    .pulse { width:11px; height:11px; border-radius:50%; background:#dc2626; animation: pulse 1s ease-in-out infinite; }
    @keyframes pulse { 50% { opacity:.25; box-shadow:0 0 0 5px rgb(220 38 38 / 18%); } }
    @media (prefers-reduced-motion: reduce) { .pulse { animation:none; } }
  `,
})
export class AudioRecorder implements OnDestroy {
  protected readonly i18n = inject(I18n);
  readonly fileName = input<string | null>(null);
  readonly disabled = input(false);
  readonly recorded = output<File>();
  readonly discarded = output<void>();
  protected readonly recording = signal(false);
  protected readonly starting = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly previewUrl = signal<string | null>(null);
  private recorder: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private chunks: Blob[] = [];
  private startedAt = 0;
  private recorderFailed = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;
  private previousFileName: string | null = null;
  constructor() {
    effect(() => {
      const name = this.fileName();
      if (this.previousFileName && !name) this.clearPreview();
      this.previousFileName = name;
    });
  }

  protected discard(): void { this.clearPreview(); this.discarded.emit(); }
  private clearPreview(): void {
    const url = this.previewUrl();
    if (url) URL.revokeObjectURL(url);
    this.previewUrl.set(null);
  }

  protected async start(): Promise<void> {
    if (this.recording() || this.starting()) return;
    this.error.set(null);
    if (this.previewUrl()) this.discarded.emit();
    this.clearPreview();
    if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      this.error.set('jobAudioRecordingUnavailable');
      return;
    }
    // Some Chromium builds claim generic audio/mp4 support but mux Opus into MP4, which the API
    // correctly refuses. Prefer the browser's native WebM/Opus output when available.
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4;codecs=mp4a.40.2', 'audio/mp4'].find((type) => MediaRecorder.isTypeSupported(type));
    if (!mimeType) {
      this.error.set('jobAudioRecordingUnavailable');
      return;
    }

    this.starting.set(true);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (this.destroyed) { stream.getTracks().forEach((track) => track.stop()); return; }
      this.stream = stream;
      const recorder = new MediaRecorder(stream, { mimeType });
      this.recorder = recorder;
      this.chunks = [];
      this.recorderFailed = false;
      recorder.ondataavailable = (event) => { if (event.data.size) this.chunks.push(event.data); };
      recorder.onerror = () => {
        this.recorderFailed = true;
        this.error.set('jobAudioRecordingFailed');
        if (recorder.state !== 'inactive') recorder.stop();
      };
      recorder.onstop = () => { void this.finish(recorder, mimeType); };
      recorder.start(1000);
      this.startedAt = Date.now();
      this.recording.set(true);
      this.timer = setTimeout(() => this.stop(), maxDurationMs);
    } catch {
      this.error.set('jobAudioRecordingFailed');
      this.releaseStream();
    } finally {
      this.starting.set(false);
    }
  }

  protected stop(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop();
  }

  private async finish(recorder: MediaRecorder, mimeType: string): Promise<void> {
    this.recording.set(false);
    this.recorder = null;
    this.releaseStream();
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    if (this.destroyed || this.recorderFailed) return;
    const durationMs = Date.now() - this.startedAt;
    if (durationMs < 1000) { this.error.set('jobAudioRecordingTooShort'); return; }
    try {
      let source = new Blob(this.chunks, { type: recorder.mimeType || mimeType });
      const type = source.type.split(';')[0]?.toLowerCase();
      if (!['audio/mp4', 'audio/webm'].includes(type ?? '')) throw new Error('UNSUPPORTED_AUDIO');
      if (type === 'audio/webm') source = await webmWithDuration(source, durationMs);
      if (source.size > maxBytes) { this.error.set('jobAudioRecordingTooLarge'); return; }
      const file = new File([source], `audio-note-${Date.now()}.${type === 'audio/webm' ? 'webm' : 'm4a'}`, { type: type! });
      this.previewUrl.set(URL.createObjectURL(file));
      this.recorded.emit(file);
    } catch {
      this.error.set('jobAudioRecordingUnavailable');
    } finally {
      this.chunks = [];
    }
  }

  private releaseStream(): void {
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.timer) clearTimeout(this.timer);
    if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop();
    this.releaseStream();
    this.clearPreview();
  }
}
