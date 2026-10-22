package com.akash.reporting.config;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import com.akash.reporting.storage.S3ReportStorage;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageUploadWireTest {

    @Test
    void knownLengthPartHasUnframedBytesAndAValidChecksum() throws Exception {
        var body = new AtomicReference<byte[]>();
        var headers = new AtomicReference<Headers>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String xml;
            if (query != null && query.startsWith("uploads")) {
                xml = "<InitiateMultipartUploadResult><Bucket>reports</Bucket><Key>test.csv</Key>"
                        + "<UploadId>test-upload</UploadId></InitiateMultipartUploadResult>";
            } else if (query != null && query.contains("partNumber=")) {
                headers.set(exchange.getRequestHeaders());
                body.set(exchange.getRequestBody().readAllBytes());
                exchange.getResponseHeaders().set("ETag", "\"test-part\"");
                xml = "";
            } else {
                exchange.getRequestBody().readAllBytes();
                xml = "<CompleteMultipartUploadResult><Bucket>reports</Bucket><Key>test.csv</Key>"
                        + "<ETag>test-object</ETag></CompleteMultipartUploadResult>";
            }
            byte[] response = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var settings = new StorageProperties(URI.create("http://localhost:" + server.getAddress().getPort()),
                    "us-east-1", "reports", "key", "secret", true, 5242880, 20000000, Duration.ofMinutes(5));
            byte[] expected = new byte[262151];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i % 251);
            try (var client = new StorageConfiguration().reportS3Client(settings);
                    var upload = new S3ReportStorage(client, null, settings).begin("test.csv")) {
                upload.write(expected);
                assertThat(upload.complete()).isEqualTo(expected.length);
            }
            assertThat(body.get()).isEqualTo(expected);
            assertThat(headers.get().getFirst("Content-Length")).isEqualTo(Integer.toString(expected.length));
            assertThat(headers.get().getFirst("Content-Encoding")).isNull();
            assertThat(headers.get().getFirst("x-amz-content-sha256")).doesNotStartWith("STREAMING-");
            var crc = new CRC32();
            crc.update(expected);
            String checksum = Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
            assertThat(headers.get().getFirst("x-amz-checksum-crc32")).isEqualTo(checksum);
        } finally {
            server.stop(0);
        }
    }
}
