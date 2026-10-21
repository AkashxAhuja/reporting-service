package com.akash.reporting.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.akash.reporting.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;

// One fixed-size byte buffer, synchronous parts, and at most 10000 small ETag entries.
final class S3MultipartUpload extends ReportStorage.Upload {
    private static final Logger log = LoggerFactory.getLogger(S3MultipartUpload.class);
    private final S3Client client;
    private final StorageProperties properties;
    private final String key;
    private final String uploadId;
    private final byte[] buffer;
    private final List<CompletedPart> parts = new ArrayList<>();
    private int used;
    private long total;
    private boolean completed;
    private boolean closed;

    S3MultipartUpload(S3Client client, StorageProperties properties, String key) {
        this.client = client;
        this.properties = properties;
        this.key = key;
        buffer = new byte[properties.partSizeBytes()];
        uploadId = client.createMultipartUpload(b -> b.bucket(properties.bucket()).key(key)
                .contentType("text/csv; charset=utf-8")).uploadId();
    }

    @Override
    public void write(int value) throws IOException { write(new byte[] {(byte) value}, 0, 1); }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        checkOpen();
        if (length > properties.maxOutputBytes() - total) throw new IOException("Report output limit exceeded");
        while (length > 0) {
            int count = Math.min(length, buffer.length - used);
            System.arraycopy(bytes, offset, buffer, used, count);
            used += count; offset += count; length -= count; total += count;
            if (used == buffer.length) uploadPart();
        }
    }

    private void uploadPart() throws IOException {
        if (parts.size() >= 10000) throw new IOException("Multipart part limit exceeded");
        int number = parts.size() + 1;
        var response = client.uploadPart(b -> b.bucket(properties.bucket()).key(key).uploadId(uploadId)
                .partNumber(number), RequestBody.fromInputStream(new ByteArrayInputStream(buffer, 0, used), used));
        parts.add(CompletedPart.builder().partNumber(number).eTag(response.eTag()).build());
        used = 0;
    }

    @Override
    public long complete() throws IOException {
        checkOpen();
        if (used > 0 || parts.isEmpty()) uploadPart();
        client.completeMultipartUpload(b -> b.bucket(properties.bucket()).key(key).uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
        completed = true;
        return total;
    }

    private void checkOpen() throws IOException {
        if (completed || closed) throw new IOException("Upload is already closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (!completed) {
            try {
                client.abortMultipartUpload(b -> b.bucket(properties.bucket()).key(key).uploadId(uploadId));
            } catch (RuntimeException failure) {
                log.warn("Could not abort incomplete upload {}", key, failure);
            }
        }
    }
}
