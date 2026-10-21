package com.akash.reporting.storage;

import java.net.URI;
import com.akash.reporting.config.StorageProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
public class S3ReportStorage implements ReportStorage {
    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3ReportStorage(S3Client client, S3Presigner presigner, StorageProperties properties) {
        this.client = client; this.presigner = presigner; this.properties = properties;
    }

    @Override
    public Upload begin(String objectKey) { return new S3MultipartUpload(client, properties, objectKey); }

    @Override
    public URI download(String objectKey, String fileName) {
        var request = GetObjectRequest.builder().bucket(properties.bucket()).key(objectKey)
                .responseContentType("text/csv; charset=utf-8")
                .responseContentDisposition("attachment; filename=\"" + fileName + "\"").build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.downloadTtl()).getObjectRequest(request).build()).url().toString());
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(b -> b.bucket(properties.bucket()).key(objectKey));
    }
}
