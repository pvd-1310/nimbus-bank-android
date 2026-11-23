import { DeleteObjectCommand, ListObjectVersionsCommand, GetObjectCommand, HeadObjectCommand, S3ServiceException, type S3Client } from "@aws-sdk/client-s3";
import { createPresignedPost } from "@aws-sdk/s3-presigned-post";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { check, Fault } from "../../shared/errors.js";
import type { EvidenceProvider } from "./ekyc.service.js";

const signatures = {
  "image/jpeg": Buffer.from([0xff, 0xd8, 0xff]),
  "image/png": Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
};

export class S3Evidence implements EvidenceProvider {
  constructor(private client: S3Client, private bucket: string) {}

  upload({ key, mime, bytes }: Parameters<EvidenceProvider["upload"]>[0]) {
    return createPresignedPost(this.client, {
      Bucket: this.bucket,
      Key: key,
      Expires: 180,
      Fields: { "Content-Type": mime },
      Conditions: [["eq", "$Content-Type", mime], ["content-length-range", bytes, bytes]],
    });
  }

  async verify({ key, mime, bytes }: Parameters<EvidenceProvider["verify"]>[0]) {
    const object = { Bucket: this.bucket, Key: key };
    const signature = signatures[mime];
    check(bytes >= signature.length, "INVALID_EVIDENCE");
    try {
      const head = await this.client.send(new HeadObjectCommand(object));
      const versionId = head.VersionId;
      check(versionId && versionId !== "null" && head.ContentType === mime && head.ContentLength === bytes, "INVALID_EVIDENCE");
      const last = signature.length - 1;
      const data = await this.client.send(new GetObjectCommand({
        ...object,
        VersionId: versionId,
        Range: `bytes=0-${last}`,
      }));
      const body = await data.Body?.transformToByteArray();
      check(data.VersionId === versionId && data.ContentRange === `bytes 0-${last}/${bytes}`
        && data.ContentType === mime && body && signature.equals(body), "INVALID_EVIDENCE");
      return versionId;
    } catch (error) {
      if (error instanceof S3ServiceException && (error.$metadata.httpStatusCode === 404 || error.$metadata.httpStatusCode === 416)) {
        throw new Fault("INVALID_EVIDENCE");
      }
      throw error;
    }
  }

  async read({ key, versionId, bytes, mime }: Parameters<NonNullable<EvidenceProvider["read"]>>[0]) {
    check(versionId && versionId !== "null" && bytes > 0 && bytes <= 5_000_000, "INVALID_EVIDENCE");
    const data = await this.client.send(new GetObjectCommand({
      Bucket: this.bucket, Key: key, VersionId: versionId,
    }), { abortSignal: AbortSignal.timeout(8000) });
    check(data.ContentLength === bytes && data.ContentType === mime && data.VersionId === versionId, "INVALID_EVIDENCE");
    const body = await data.Body?.transformToByteArray();
    check(body?.length === bytes, "INVALID_EVIDENCE");
    return Buffer.from(body);
  }

  async removeVersions(key: string) {
    // Re-list after each batch so interrupted cleanup is safe to repeat.
    while (true) {
      const page = await this.client.send(new ListObjectVersionsCommand({
        Bucket: this.bucket, Prefix: key, MaxKeys: 1000,
      }));
      const objects = [...(page.Versions ?? []), ...(page.DeleteMarkers ?? [])].filter((obj) => obj.Key === key);
      if (!objects.length) return;
      for (const obj of objects) {
        check(obj.VersionId, "INVALID_OBJECT_VERSION");
        await this.client.send(new DeleteObjectCommand({
          Bucket: this.bucket, Key: key, VersionId: obj.VersionId,
        }));
      }
    }
  }

  download({ key, versionId }: Parameters<EvidenceProvider["download"]>[0]) {
    check(versionId && versionId !== "null", "INVALID_EVIDENCE");
    return getSignedUrl(this.client, new GetObjectCommand({
      Bucket: this.bucket,
      Key: key,
      VersionId: versionId,
      ResponseCacheControl: "private, no-store",
    }), { expiresIn: 120 });
  }
}
