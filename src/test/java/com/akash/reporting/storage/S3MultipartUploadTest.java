package com.akash.reporting.storage;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.akash.reporting.config.StorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3MultipartUploadTest {
    @Test void uploadsBoundedPartsInOrderAndCompletesWithTheirEtags() throws Exception {
        var client = client(); var lengths = new ArrayList<Long>();
        when(client.uploadPart(any(Consumer.class), any(RequestBody.class))).thenAnswer(call -> {
            RequestBody body = call.getArgument(1);
            lengths.add(body.contentLength());
            try (var input = body.contentStreamProvider().newStream()) {
                assertThat(input.readAllBytes()).hasSize((int) body.contentLength());
            }
            return UploadPartResponse.builder().eTag("part-" + lengths.size()).build();
        });
        try (var upload = new S3MultipartUpload(client, properties(20_000_000), "attempt.csv")) {
            upload.write(new byte[5_242_880]); upload.write(new byte[123]);
            assertThat(upload.complete()).isEqualTo(5_243_003);
        }
        assertThat(lengths).containsExactly(5_242_880L, 123L);
        verify(client).completeMultipartUpload(any(Consumer.class));
        verify(client, never()).abortMultipartUpload(any(Consumer.class));
    }

    @Test void aFailedPartIsAbortedAndNeverCompleted() {
        var client = client();
        when(client.uploadPart(any(Consumer.class), any(RequestBody.class))).thenThrow(new IllegalStateException("Upload failed"));
        try (var upload = new S3MultipartUpload(client, properties(20_000_000), "attempt.csv")) {
            assertThatThrownBy(() -> upload.write(new byte[5_242_880])).isInstanceOf(IllegalStateException.class);
        }
        verify(client).abortMultipartUpload(any(Consumer.class));
        verify(client, never()).completeMultipartUpload(any(Consumer.class));
    }

    @Test void outputLimitAbortsInsteadOfSilentlyTruncatingTheCsv() {
        var client = client();
        try (var upload = new S3MultipartUpload(client, properties(10), "attempt.csv")) {
            assertThatThrownBy(() -> upload.write(new byte[11])).isInstanceOf(IOException.class);
        }
        verify(client).abortMultipartUpload(any(Consumer.class));
        verify(client, never()).completeMultipartUpload(any(Consumer.class));
    }

    private S3Client client() {
        var client = mock(S3Client.class);
        when(client.createMultipartUpload(any(Consumer.class))).thenReturn(
                CreateMultipartUploadResponse.builder().uploadId("upload-id").build());
        return client;
    }
    private StorageProperties properties(long limit) {
        return new StorageProperties(URI.create("http://localhost:8333"), "us-east-1", "reports", "key", "secret",
                true, 5_242_880, limit, Duration.ofMinutes(5));
    }
}
