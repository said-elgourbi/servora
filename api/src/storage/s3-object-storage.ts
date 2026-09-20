import {
  GetObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import type { S3StorageConfig } from './storage-config.js';
import {
  ObjectNotFoundError,
  ObjectStorageError,
  type ObjectStorage,
  type PutObjectInput,
  type StoredObject,
} from './object-storage.js';

/**
 * `ObjectStorage` over any S3-compatible service (`ADR-013` D1, D6.1).
 *
 * The AWS SDK's S3 client is used as the **S3 client**, not as an Amazon-only dependency: MinIO
 * locally and a provider in production are reached through the same contract, and no MinIO concept
 * appears in the domain or application layer. Two behaviours `ADR-013` fixes are applied here:
 *
 * - **Path-style addressing** (`S3_FORCE_PATH_STYLE`), because a bucket-prefixed host does not
 *   resolve against MinIO (`ADR-013` D6.3).
 * - **Checksums only when required** (`ADR-013` D6.7). Recent SDK releases send default
 *   `x-amz-checksum-*` values and unsigned-trailer payloads that several S3-compatible services
 *   reject, so the calculation and the validation are pinned to `WHEN_REQUIRED`.
 *
 * A provider failure is mapped onto the port's own error types, so no provider error shape reaches
 * the domain (`dev.md` §7).
 */
export class S3ObjectStorage implements ObjectStorage {
  private readonly client: S3Client;

  constructor(private readonly config: S3StorageConfig) {
    this.client = new S3Client({
      endpoint: config.endpoint,
      region: config.region,
      forcePathStyle: config.forcePathStyle,
      credentials: {
        accessKeyId: config.accessKey,
        secretAccessKey: config.secretKey,
      },
      requestChecksumCalculation: 'WHEN_REQUIRED',
      responseChecksumValidation: 'WHEN_REQUIRED',
    });
  }

  async putObject(input: PutObjectInput): Promise<void> {
    try {
      await this.client.send(
        new PutObjectCommand({
          Bucket: this.config.bucket,
          Key: input.key,
          Body: input.body,
          ContentType: input.contentType,
        }),
      );
    } catch {
      throw new ObjectStorageError();
    }
  }

  async getObject(key: string): Promise<StoredObject> {
    try {
      const response = await this.client.send(
        new GetObjectCommand({ Bucket: this.config.bucket, Key: key }),
      );
      const body = await response.Body?.transformToByteArray();
      if (body === undefined) {
        throw new ObjectNotFoundError();
      }
      return {
        body: Buffer.from(body),
        contentType: response.ContentType ?? 'application/octet-stream',
        byteSize: body.byteLength,
      };
    } catch (error) {
      if (error instanceof ObjectNotFoundError) {
        throw error;
      }
      if (isNotFound(error)) {
        throw new ObjectNotFoundError();
      }
      throw new ObjectStorageError();
    }
  }
}

/** Whether a provider's answer says the key is not stored, however it names that. */
function isNotFound(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const name = (error as { name?: unknown }).name;
  if (name === 'NoSuchKey' || name === 'NotFound') {
    return true;
  }
  const status = (error as { $metadata?: { httpStatusCode?: unknown } }).$metadata
    ?.httpStatusCode;
  return status === 404;
}
