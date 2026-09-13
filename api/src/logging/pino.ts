import { LoggerService } from '@nestjs/common';
import {
  pino,
  type LogFn,
  type Logger as PinoLogger,
  type LoggerOptions,
} from 'pino';
import type { AppConfig } from '../config/configuration.js';

/**
 * Creates the application-wide pino logger.
 *
 * - Production output is plain JSON so container/platform log ingestion is easy.
 * - Non-production output is pretty-printed for comfortable local development.
 * - Sensitive request headers are always redacted regardless of the target.
 */
export function createPinoLogger(config: AppConfig): PinoLogger {
  const options: LoggerOptions = {
    level: config.logLevel,
    base: { service: 'servora-api' },
    timestamp: pino.stdTimeFunctions.isoTime,
    redact: {
      paths: [
        'req.headers.authorization',
        'req.headers.cookie',
        'res.headers["set-cookie"]',
      ],
      censor: '[Redacted]',
    },
  };

  if (config.nodeEnv !== 'production') {
    options.transport = {
      target: 'pino-pretty',
      options: { singleLine: true, colorize: true },
    };
  }

  return pino(options);
}

/**
 * Adapts pino to NestJS's LoggerService so framework boot/shutdown messages
 * are structured logs instead of a second, inconsistent logging channel.
 */
export class PinoNestLogger implements LoggerService {
  constructor(private readonly logger: PinoLogger) {}

  log(message: unknown, ...optionalParams: unknown[]): void {
    this.write('info', message, optionalParams);
  }

  error(message: unknown, ...optionalParams: unknown[]): void {
    this.write('error', message, optionalParams);
  }

  warn(message: unknown, ...optionalParams: unknown[]): void {
    this.write('warn', message, optionalParams);
  }

  debug?(message: unknown, ...optionalParams: unknown[]): void {
    this.write('debug', message, optionalParams);
  }

  verbose?(message: unknown, ...optionalParams: unknown[]): void {
    this.write('trace', message, optionalParams);
  }

  fatal?(message: unknown, ...optionalParams: unknown[]): void {
    this.write('fatal', message, optionalParams);
  }

  private write(
    level: 'fatal' | 'error' | 'warn' | 'info' | 'debug' | 'trace',
    message: unknown,
    optionalParams: unknown[],
  ): void {
    const params = [...optionalParams];
    // Nest conventionally passes the logger context as the final string param.
    let context: string | undefined;
    const last = params.at(-1);
    if (typeof last === 'string') {
      context = last;
      params.pop();
    }

    const dest = context ? this.logger.child({ context }) : this.logger;
    // Pino level methods rely on `this` (child bindings/prefix), so each level
    // is bound to the logger before being invoked.
    const levels = {
      fatal: dest.fatal.bind(dest),
      error: dest.error.bind(dest),
      warn: dest.warn.bind(dest),
      info: dest.info.bind(dest),
      debug: dest.debug.bind(dest),
      trace: dest.trace.bind(dest),
    } as const;
    const logAt: LogFn = levels[level];

    if (message instanceof Error) {
      logAt({
        err: {
          name: message.name,
          message: message.message,
          stack: message.stack,
        },
      });
      return;
    }
    if (typeof message === 'object' && message !== null) {
      logAt(message as object);
      return;
    }
    logAt(String(message));
  }
}
