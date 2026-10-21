package com.akash.reporting.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;

public interface ReportStorage {
    Upload begin(String objectKey);
    URI download(String objectKey, String fileName);
    void delete(String objectKey);

    abstract class Upload extends OutputStream {
        public abstract long complete() throws IOException;
    }
}
