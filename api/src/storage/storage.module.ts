import { Module } from '@nestjs/common';
import {
  OBJECT_STORAGE,
  ObjectStorageError,
  type ObjectStorage,
  type PutObjectInput,
  type StoredObject,
} from './object-storage.js';
import { S3ObjectStorage } from './s3-object-storage.js';
import { loadStorageConfig } from './storage-config.js';

/**
 * Binds the object-storage port to an implementation (`ADR-013` D6).
 *
 * Selection and credentials come from configuration (`STORAGE_PROVIDER`, `S3_*`), and
 * `loadStorageConfig` fails at startup when a selected provider cannot be configured, so a
 * deployment never runs with a storage path that silently cannot store evidence.
 *
 * The default is the no-op implementation: a machine with no storage credentials boots and runs its
 * tests, and an evidence upload is **refused** rather than reported as stored. Nothing in Servora
 * may claim evidence was kept when it was not (`BR-014`, `BR-015`, `BR-042`).
 */
@Module({
  providers: [
    {
      provide: OBJECT_STORAGE,
      useFactory: (): ObjectStorage => {
        const config = loadStorageConfig();

        switch (config.provider) {
          case 's3':
            return config.s3 === null
              ? new UnavailableObjectStorage()
              : new S3ObjectStorage(config.s3);
          case 'noop':
            return new UnavailableObjectStorage();
        }
      },
    },
  ],
  exports: [OBJECT_STORAGE],
})
export class StorageModule {}

/**
 * The implementation a deployment without storage configuration gets.
 *
 * It fails every operation instead of accepting one, because an upload it "accepted" would be
 * evidence the backend does not hold.
 */
class UnavailableObjectStorage implements ObjectStorage {
  async putObject(_input: PutObjectInput): Promise<void> {
    throw new ObjectStorageError();
  }

  async getObject(_key: string): Promise<StoredObject> {
    throw new ObjectStorageError();
  }
}
