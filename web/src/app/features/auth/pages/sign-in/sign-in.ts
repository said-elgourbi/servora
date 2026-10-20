import { PageTitle } from '../../../../shared/ui/page-title';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { I18n } from '../../../../core/i18n/i18n';
import type { TranslationKey } from '../../../../core/i18n/en';
import { Preferences } from '../../../../shared/ui/preferences/preferences';
import { AuthSession } from '../../data-access/auth-session';
import { authErrorKey } from '../../data-access/auth-errors';
import { safeReturnUrl } from '../../auth.guards';

@Component({
  selector: 'app-sign-in',
  imports: [
    PageTitle,
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    Preferences,
  ],
  templateUrl: './sign-in.html',
  styleUrl: './sign-in.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignIn {
  protected readonly i18n = inject(I18n);
  protected readonly session = inject(AuthSession);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly method = signal<'email' | 'phone'>('email');
  protected readonly codeStep = signal(false);
  protected readonly busy = signal(false);
  protected readonly visiblePassword = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly remaining = signal(0);
  private resendAt = 0;
  protected readonly emailForm = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email, Validators.maxLength(320)],
    }),
    password: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(1024)],
    }),
  });
  protected readonly phoneForm = new FormGroup({
    phone: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^\+[1-9]\d{7,14}$/)],
    }),
  });
  protected readonly codeForm = new FormGroup({
    code: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^\d{6}$/)],
    }),
  });
  constructor() {
    const timer = window.setInterval(
      () => this.remaining.set(Math.max(0, Math.ceil((this.resendAt - Date.now()) / 1000))),
      1000,
    );
    inject(DestroyRef).onDestroy(() => window.clearInterval(timer));
  }
  protected choose(method: 'email' | 'phone'): void {
    if (this.busy()) return;
    this.method.set(method);
    this.error.set(null);
    this.codeForm.reset();
    this.codeStep.set(false);
    this.emailForm.controls.password.reset();
  }
  protected changeNumber(): void {
    this.codeForm.reset();
    this.codeStep.set(false);
    this.error.set(null);
  }
  protected async emailSignIn(): Promise<void> {
    this.emailForm.markAllAsTouched();
    if (this.emailForm.invalid) return;
    await this.submit(async () => {
      const { email, password } = this.emailForm.getRawValue();
      await this.session.signIn(email.trim(), password);
      this.emailForm.controls.password.reset();
      await this.enter();
    });
  }
  protected async requestCode(): Promise<void> {
    this.phoneForm.markAllAsTouched();
    if (this.phoneForm.invalid || (this.codeStep() && this.remaining() > 0)) return;
    await this.submit(async () => {
      await this.session.requestSms(this.phoneForm.controls.phone.value);
      this.codeForm.reset();
      this.codeStep.set(true);
      this.startCooldown();
    });
  }
  protected async verifyCode(): Promise<void> {
    this.codeForm.markAllAsTouched();
    if (this.codeForm.invalid) return;
    await this.submit(async () => {
      await this.session.verifySms(
        this.phoneForm.controls.phone.value,
        this.codeForm.controls.code.value,
      );
      this.codeForm.reset();
      await this.enter();
    });
  }
  private async submit(action: () => Promise<void>): Promise<void> {
    if (this.busy() || this.session.loggingOut()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (error) {
      const key = authErrorKey(error);
      this.error.set(key);
      if (key === 'rateLimited' && this.method() === 'phone') this.startCooldown();
    } finally {
      this.busy.set(false);
    }
  }
  private startCooldown(): void {
    this.resendAt = Date.now() + 60000;
    this.remaining.set(60);
  }
  private async enter(): Promise<void> {
    if (this.session.authenticated())
      await this.router.navigateByUrl(
        safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')),
      );
  }
}
