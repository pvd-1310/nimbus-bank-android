import { GetObjectCommand, HeadObjectCommand, S3ServiceException, type S3Client } from "@aws-sdk/client-s3";
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

  download({ key, versionId }: Parameters<EvidenceProvider["download"]>[0]) {
    check(versionId && versionId !== "null", "INVALID_EVIDENCE");
    return getSignedUrl(this.client, new GetObjectCommand({
      Bucket: this.bucket,
      Key: key,
      VersionId: versionId,
    }), { expiresIn: 120 });
  }
}
