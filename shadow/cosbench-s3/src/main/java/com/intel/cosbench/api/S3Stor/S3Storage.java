package com.intel.cosbench.api.S3Stor;

import static com.intel.cosbench.client.S3Stor.S3Constants.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpStatus;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.intel.cosbench.api.storage.NoneStorage;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.log.Logger;

public class S3Storage extends NoneStorage {
    private static final long MULTIPART_PART_SIZE = 5L * 1024 * 1024;

    private AmazonS3 client;

    @Override
    public void init(Config config, Logger logger) {
        super.init(config, logger);

        int timeout = config.getInt(CONN_TIMEOUT_KEY, CONN_TIMEOUT_DEFAULT);
        String endpoint = config.get(ENDPOINT_KEY, ENDPOINT_DEFAULT);
        String accessKey = config.get(AUTH_USERNAME_KEY, AUTH_USERNAME_DEFAULT);
        String secretKey = config.get(AUTH_PASSWORD_KEY, AUTH_PASSWORD_DEFAULT);
        boolean pathStyleAccess = config.getBoolean(PATH_STYLE_ACCESS_KEY, PATH_STYLE_ACCESS_DEFAULT);
        String proxyHost = config.get(PROXY_HOST_KEY, "");
        String proxyPort = config.get(PROXY_PORT_KEY, "");

        parms.put(CONN_TIMEOUT_KEY, timeout);
        parms.put(ENDPOINT_KEY, endpoint);
        parms.put(AUTH_USERNAME_KEY, accessKey);
        parms.put(AUTH_PASSWORD_KEY, secretKey);
        parms.put(PATH_STYLE_ACCESS_KEY, pathStyleAccess);
        parms.put(PROXY_HOST_KEY, proxyHost);
        parms.put(PROXY_PORT_KEY, proxyPort);
        logger.debug("using S3 config: endpoint={}, timeout={}, path_style_access={}, proxyhost={}, proxyport={}",
                new Object[] {endpoint, timeout, pathStyleAccess, proxyHost, proxyPort});

        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setConnectionTimeout(timeout);
        clientConfiguration.setSocketTimeout(timeout);
        clientConfiguration.setUseExpectContinue(false);
        clientConfiguration.setSignerOverride("S3SignerType");
        if (!proxyHost.isEmpty() && !proxyPort.isEmpty()) {
            clientConfiguration.setProxyHost(proxyHost);
            clientConfiguration.setProxyPort(Integer.parseInt(proxyPort));
        }

        AWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
        client = new AmazonS3Client(credentials, clientConfiguration);
        client.setEndpoint(endpoint);
        client.setS3ClientOptions(new S3ClientOptions().withPathStyleAccess(pathStyleAccess));
    }

    @Override
    public void dispose() {
        super.dispose();
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }

    @Override
    public InputStream getObject(String container, String object, Config config) {
        super.getObject(container, object, config);
        try {
            S3Object result = client.getObject(container, object);
            return result.getObjectContent();
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    @Override
    public InputStream getList(String container, String object, Config config) {
        super.getList(container, object, config);
        try {
            ByteArrayOutputStream listing = new ByteArrayOutputStream();
            String continuationToken = null;
            do {
                ListObjectsV2Request request = new ListObjectsV2Request()
                        .withBucketName(container)
                        .withPrefix(object == null ? "" : object)
                        .withMaxKeys(1000)
                        .withContinuationToken(continuationToken);
                ListObjectsV2Result result = client.listObjectsV2(request);
                for (S3ObjectSummary summary : result.getObjectSummaries()) {
                    listing.write(summary.getKey().getBytes(StandardCharsets.UTF_8));
                    listing.write('\n');
                }
                continuationToken = result.isTruncated() ? result.getNextContinuationToken() : null;
            } while (continuationToken != null);
            return new ByteArrayInputStream(listing.toByteArray());
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    @Override
    public void createContainer(String container, Config config) {
        super.createContainer(container, config);
        try {
            try {
                client.headBucket(new HeadBucketRequest(container));
            } catch (AmazonS3Exception error) {
                if (error.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                    client.createBucket(container);
                } else if (error.getStatusCode() != HttpStatus.SC_MOVED_PERMANENTLY) {
                    throw error;
                }
            }
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    @Override
    public void createObject(String container, String object, InputStream data, long length, Config config) {
        super.createObject(container, object, data, length, config);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(length);
            metadata.setContentType("application/octet-stream");
            client.putObject(container, object, data, metadata);
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    @Override
    public void createMultipartObject(String container, String object, InputStream data, long length, Config config) {
        super.createMultipartObject(container, object, data, length, config);
        if (length == 0) {
            createObject(container, object, data, length, config);
            return;
        }
        if (length < 0) {
            throw new StorageException(new IllegalArgumentException("Multipart object length must not be negative"));
        }

        String uploadId = null;
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(length);
            metadata.setContentType("application/octet-stream");
            InitiateMultipartUploadResult initiated = client.initiateMultipartUpload(
                    new InitiateMultipartUploadRequest(container, object, metadata));
            uploadId = initiated.getUploadId();

            List<PartETag> parts = new ArrayList<>();
            long remaining = length;
            for (int partNumber = 1; remaining > 0; partNumber++) {
                long partSize = Math.min(MULTIPART_PART_SIZE, remaining);
                UploadPartRequest request = new UploadPartRequest()
                        .withBucketName(container)
                        .withKey(object)
                        .withUploadId(uploadId)
                        .withPartNumber(partNumber)
                        .withInputStream(data)
                        .withPartSize(partSize);
                request.getRequestClientOptions().setReadLimit((int) Math.min(partSize + 1, Integer.MAX_VALUE));
                UploadPartResult uploaded = client.uploadPart(request);
                parts.add(uploaded.getPartETag());
                remaining -= partSize;
            }

            client.completeMultipartUpload(new CompleteMultipartUploadRequest(container, object, uploadId, parts));
            uploadId = null;
        } catch (Exception error) {
            if (uploadId != null) {
                try {
                    client.abortMultipartUpload(new AbortMultipartUploadRequest(container, object, uploadId));
                } catch (Exception abortError) {
                    error.addSuppressed(abortError);
                }
            }
            throw new StorageException(error);
        }
    }

    @Override
    public void deleteContainer(String container, Config config) {
        super.deleteContainer(container, config);
        try {
            if (client.doesBucketExist(container)) {
                client.deleteBucket(container);
            }
        } catch (AmazonS3Exception error) {
            if (error.getStatusCode() != HttpStatus.SC_NOT_FOUND) {
                throw new StorageException(error);
            }
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    @Override
    public void deleteObject(String container, String object, Config config) {
        super.deleteObject(container, object, config);
        try {
            client.deleteObject(container, object);
        } catch (AmazonS3Exception error) {
            if (error.getStatusCode() != HttpStatus.SC_NOT_FOUND) {
                throw new StorageException(error);
            }
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    @Override
    public void headObject(String container, String object, Config config) {
        super.headObject(container, object, config);
        try {
            client.getObjectMetadata(new GetObjectMetadataRequest(container, object));
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }
}
