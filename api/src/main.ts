import { NestFactory } from '@nestjs/core';
import { pinoHttp } from 'pino-http';
import { AppModule } from './app.module.js';
import { loadConfig } from './config/configuration.js';
import { createPinoLogger, PinoNestLogger } from './logging/pino.js';

async function bootstrap(): Promise<void> {
  const config = loadConfig();
  const logger = createPinoLogger(config);

  const app = await NestFactory.create(AppModule, {
    logger: new PinoNestLogger(logger),
  });

  // Structured HTTP request logging with per-request correlation ids.
  app.use(pinoHttp({ logger }));

  app.enableShutdownHooks();

  await app.listen(config.port, '0.0.0.0');
  logger.info(
    { port: config.port, env: config.nodeEnv },
    'Servora API is listening',
  );
}

void bootstrap();
