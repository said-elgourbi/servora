import {
  OBJECT_STORAGE,
  ObjectNotFoundError,
  ObjectStorageError,
  type ObjectStorage,
  type PutObjectInput,
  type StoredObject,
} from '../../src/storage/object-storage.js';

/**
 * An in-memory `ObjectStorage` for tests.
 *
 * It stands in for the S3 adapter so the API's own behaviour can be asserted without a running
 * object store: which key the API derived, what bytes it stored, and how many times it stored them.
 * That last count is what proves an idempotent replay does not upload the evidence twice.
 */
export class FakeObjectStorage implements ObjectStorage {
  private readonly objects = new Map<string, StoredObject>();
  readonly puts: PutObjectInput[] = [];

  /**
   * Every key the API asked for, in order.
   *
   * It is what lets a test assert that an operation never touched the store — a removal, for example,
   * records a decision about evidence rather than purging its bytes (`BR-089`, `BR-090`).
   */
  readonly gets: string[] = [];

  /** When set, every operation fails the way an unreachable provider would. */
  failWith: Error | null = null;

  async putObject(input: PutObjectInput): Promise<void> {
    if (this.failWith !== null) {
      throw this.failWith;
    }
    this.puts.push(input);
    this.objects.set(input.key, {
      body: input.body,
      contentType: input.contentType,
      byteSize: input.body.byteLength,
    });
  }

  async getObject(key: string): Promise<StoredObject> {
    if (this.failWith !== null) {
      throw this.failWith;
    }
    this.gets.push(key);
    const object = this.objects.get(key);
    if (object === undefined) {
      throw new ObjectNotFoundError();
    }
    return object;
  }

  /** Removes every stored object, so a test can assert a missing object honestly. */
  clear(): void {
    this.objects.clear();
    this.puts.length = 0;
    this.gets.length = 0;
  }

  /** A provider failure, for the tests that must see the write refused rather than faked. */
  static unavailable(): ObjectStorageError {
    return new ObjectStorageError();
  }

  /** Binds this fake to the `OBJECT_STORAGE` token in a test module. */
  static readonly token = OBJECT_STORAGE;
}
