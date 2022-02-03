/**

Copyright 2021 EHualu Corporation, All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.intel.cosbench.api.S3StorV2;

import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsProfileRegionProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.utils.AttributeMap;

import com.intel.cosbench.api.storage.*;
import com.intel.cosbench.api.context.*;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.log.Logger;
import static com.intel.cosbench.client.S3StorV2.S3ConstantsV2.*;

public class S3StorageV2 extends NoneStorage {

	private int timeout;

	private String accessKey;
	private String secretKey;
	private String endpoint;

	private S3Client client;

	@Override
	public void init(Config config, Logger logger) {
		super.init(config, logger);

		timeout = config.getInt(CONN_TIMEOUT_KEY, CONN_TIMEOUT_DEFAULT);

		parms.put(CONN_TIMEOUT_KEY, timeout);

		endpoint = config.get(ENDPOINT_KEY, ENDPOINT_DEFAULT);
		accessKey = config.get(AUTH_USERNAME_KEY, AUTH_USERNAME_DEFAULT);
		secretKey = config.get(AUTH_PASSWORD_KEY, AUTH_PASSWORD_DEFAULT);

		boolean pathStyleAccess = config.getBoolean(PATH_STYLE_ACCESS_KEY, PATH_STYLE_ACCESS_DEFAULT);
		int maxConnections = config.getInt(MAX_CONNECTIONS, MAX_CONNECTIONS_DEFAULT);

		String proxyHost = config.get(PROXY_HOST_KEY, "");
		String proxyPort = config.get(PROXY_PORT_KEY, "");

		parms.put(ENDPOINT_KEY, endpoint);
		parms.put(AUTH_USERNAME_KEY, accessKey);
		parms.put(AUTH_PASSWORD_KEY, secretKey);
		parms.put(PATH_STYLE_ACCESS_KEY, pathStyleAccess);
		parms.put(MAX_CONNECTIONS, maxConnections);
		parms.put(PROXY_HOST_KEY, proxyHost);
		parms.put(PROXY_PORT_KEY, proxyPort);

		// 2021.02.14
		// You can set storage_class to other value in storage part.
		String storageClass = config.get(STORAGE_CLASS_KEY, STORAGE_CLASS_DEFAULT);
		parms.put(STORAGE_CLASS_KEY, storageClass);

		// 2021.07.11
		// You can set restore_days to other value(int) in storage part.
		int restoreDays = config.getInt(RESTORE_DAYS_KEY, RESTORE_DAYS_DEFAULT);
		parms.put(RESTORE_DAYS_KEY, restoreDays);

		// 2021.08.03
		// You can set part_size to other value in storage part.
		long partSize = config.getLong(PART_SIZE_KEY, PART_SIZE_DEFAULT);
		parms.put(PART_SIZE_KEY, partSize);

		// 2020.11.26
		// You can set no_verify_ssl to true in storage part to disable SSL checking.
		boolean noVerifySSL = config.getBoolean(NO_VERIFY_SSL_KEY, NO_VERIFY_SSL_DEFAULT);
		parms.put(NO_VERIFY_SSL_KEY, noVerifySSL);
		
		// 2022.02.03
		// You can set region now, and default is us-east-1.
		String awsRegion = config.get(REGION_KEY, REGION_DEFAULT);
		parms.put(REGION_KEY, awsRegion);

		initClient();

	}

	private S3Client initClient() {
		logger.debug("initialize S3 client with storage config: {}", parms);
		
		AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);
		
		S3Configuration s3Config = S3Configuration.builder()
				.pathStyleAccessEnabled(parms.getBoolean(PATH_STYLE_ACCESS_KEY))
				.build();
		
		ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
		if ((!parms.getStr(PROXY_HOST_KEY).equals("")) && (!parms.getStr(PROXY_PORT_KEY).equals(""))) {
			proxyConfig.useSystemPropertyValues(Boolean.FALSE)
			.endpoint(URI.create("http://"+parms.getStr(PROXY_HOST_KEY)+":"+parms.getInt(PROXY_PORT_KEY)));
		}
		
		SdkHttpClient httpClient;
		
		if (parms.getBoolean(NO_VERIFY_SSL_KEY, NO_VERIFY_SSL_DEFAULT)) {
			httpClient = ApacheHttpClient.builder()
					.maxConnections(parms.getInt(MAX_CONNECTIONS)) // max connections.
					// Set socket timeout for data to be transferred.
					.socketTimeout(Duration.ofMillis(parms.getInt(CONN_TIMEOUT_KEY)))
					// Set connection timeout for initially establishing a connection.
					.connectionTimeout(Duration.ofMillis(parms.getInt(CONN_TIMEOUT_KEY)))
					.expectContinueEnabled(false) // disable expect continue for HTTP/1.1.
					.proxyConfiguration(proxyConfig.build())
					.buildWithDefaults(AttributeMap.builder().put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, Boolean.TRUE).build());
		} else {
			httpClient = ApacheHttpClient.builder()
					.maxConnections(parms.getInt(MAX_CONNECTIONS)) // max connections.
					// Set socket timeout for data to be transferred.
					.socketTimeout(Duration.ofMillis(parms.getInt(CONN_TIMEOUT_KEY)))
					// Set connection timeout for initially establishing a connection.
					.connectionTimeout(Duration.ofMillis(parms.getInt(CONN_TIMEOUT_KEY)))
					.expectContinueEnabled(false) // disable expect continue for HTTP/1.1.
					.proxyConfiguration(proxyConfig.build())
					.build();
		}
		
		client = S3Client.builder()
				.credentialsProvider(StaticCredentialsProvider.create(awsCreds))
				.endpointOverride(URI.create(endpoint))
				// solve issue: 
				// https://github.com/kisscelia/cosbench-ehualu/issues/3
				.region(Region.of(parms.getStr(REGION_KEY, REGION_DEFAULT)))
				.serviceConfiguration(s3Config)
				.httpClient(httpClient)
				.build();

		logger.debug("S3 client has been initialized");

		return client;
	}

	@Override
	public void setAuthContext(AuthContext info) {
		super.setAuthContext(info);
	}

	@Override
	public void dispose() {
		super.dispose();
		client = null;
	}

	@Override
	public InputStream getObject(String container, String object, Config config) {
		super.getObject(container, object, config);
		InputStream stream = null;
		try {
			GetObjectRequest getObjectRequest = GetObjectRequest.builder()
					.bucket(container)
					.key(object)
					.build();
			ResponseBytes<GetObjectResponse> objectBytes = client.getObjectAsBytes(getObjectRequest);

			stream = objectBytes.asInputStream();

		} catch (AwsServiceException ase) {
			throw new StorageException(ase);
		} catch (SdkClientException sce) {
			throw new StorageTimeoutException(sce);
		}
		
		return stream;
	}

	@Override
	public void restoreObject(String container, String object, Config config) {
		super.restoreObject(container, object, config);
		try {
			// Create and submit a request to restore an object from Glacier/Deep Archive
			// for some days.
			RestoreRequest restoreRequest = RestoreRequest.builder()
                    .days(parms.getInt(RESTORE_DAYS_KEY))
                    .build();
			
			RestoreObjectRequest restoreObjectRequest = RestoreObjectRequest.builder()
					.bucket(container)
					.key(object)
					.restoreRequest(restoreRequest)
					.build();
			
			client.restoreObject(restoreObjectRequest);

		} catch (Exception e) {
			throw new StorageException(e);
		}
	}

	@Override
	public void createContainer(String container, Config config) {
		super.createContainer(container, config);
		try {
			container = container.split("/")[0];
			
			CreateBucketRequest createBucketRequest = CreateBucketRequest.builder().bucket(container).build();
			client.createBucket(createBucketRequest);
			
		} catch (Exception e) {
			throw new StorageException(e);
		}
	}

	@Override
	public void createObject(String container, String object, InputStream data, long length, Config config) {
		super.createObject(container, object, data, length, config);
		
		String tempStroageClass = parms.getStr(STORAGE_CLASS_KEY);
		
		String storageClass;
		
		if (tempStroageClass != "STANDARD") {
			storageClass = tempStroageClass;
		} else {
			storageClass = "STANDARD";
		}
	
		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
				.contentLength(length)
				.contentType("application/octet-stream")
				.storageClass(storageClass)
				.bucket(container)
				.key(object)
				.build();
		
		RequestBody requestBody = RequestBody.fromInputStream(data, length);

		try {
			client.putObject(putObjectRequest, requestBody);
		} catch (AwsServiceException ase) {
			throw new StorageException(ase);
		} catch (SdkClientException sce) {
			throw new StorageTimeoutException(sce);
		}
	}

	// 2021.8.3 updated, sine.
	@Override
	public void createMultipartObject(String container, String object, InputStream data, long length, Config config) {
		super.createMultipartObject(container, object, data, length, config);
		
		String tempStroageClass = parms.getStr(STORAGE_CLASS_KEY);
		
		String storageClass;
		
		if (tempStroageClass != "STANDARD") {
			storageClass = tempStroageClass;
		} else {
			storageClass = "STANDARD";
		}
		
		CreateMultipartUploadRequest createMultipartUploadRequest = CreateMultipartUploadRequest.builder()
				.contentType("application/octet-stream")
				.storageClass(storageClass)
				.bucket(container)
				.key(object)
				.build();

		// Upload the file parts.
		long partSize = parms.getLong(PART_SIZE_KEY);

		// Create a list of ETag objects. You retrieve ETags for each object part
		// uploaded,
		// then, after each individual part has been uploaded, pass the list of ETags to
		// the request to complete the upload.
		List<CompletedPart> partETags = new ArrayList<CompletedPart>();

		try {
			CreateMultipartUploadResponse response = client.createMultipartUpload(createMultipartUploadRequest);
	        String uploadId = response.uploadId();

			long position = 0;

			for (int i = 1; position < length; i++) {
				// Because the last part could be less than 5 MB, adjust the part size as
				// needed.
				partSize = Math.min(partSize, (length - position));

				// Create the request to upload a part.
				UploadPartRequest uploadRequest = UploadPartRequest.builder()
						.contentLength(partSize) // TODO: needed?
						.bucket(container)
						.key(object)
						.uploadId(uploadId)
						.partNumber(i)
						.build();
				
				// Upload the part and add the response's ETag to our list.
				RequestBody requestBody = RequestBody.fromInputStream(data, partSize);
				UploadPartResponse uploadPartResponse = client.uploadPart(uploadRequest, requestBody);
				CompletedPart tempPart = CompletedPart.builder().partNumber(i).eTag(uploadPartResponse.eTag()).build();
				
				partETags.add(tempPart);

				position += partSize;
			}
			
			CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
	                .parts(partETags)
	                .build();

			// Complete the multipart upload.
			CompleteMultipartUploadRequest compRequest = CompleteMultipartUploadRequest.builder()
					.bucket(container)
					.key(object)
					.uploadId(uploadId)
					.multipartUpload(completedMultipartUpload)
					.build();
			client.completeMultipartUpload(compRequest);

		} catch (AwsServiceException ase) {
			throw new StorageException(ase);
		} catch (SdkClientException sce) {
			throw new StorageTimeoutException(sce);
		}
	}

	@Override
	public void deleteContainer(String container, Config config) {
		super.deleteContainer(container, config);
		try {
			container = container.split("/")[0];
			
			DeleteBucketRequest deleteBucketRequest = DeleteBucketRequest.builder()
					.bucket(container)
					.build();
			
			client.deleteBucket(deleteBucketRequest);

		} catch (Exception e) {
			throw new StorageException(e);
		}
	}

	@Override
	public void deleteObject(String container, String object, Config config) {
		super.deleteObject(container, object, config);
		try {
			DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
					.bucket(container)
					.key(object)
					.build();
			
			client.deleteObject(deleteObjectRequest);
		} catch (Exception e) {
			throw new StorageException(e);
		}
	}

	@Override
	public InputStream getList(String container, String object, Config config) {
		super.getList(container, object, config);
		InputStream stream = null;
		try {
			
			ListObjectsV2Request listObjectsV2Request = ListObjectsV2Request.builder()
					.bucket(container)
					.prefix(object)
					.build();
			
			ListObjectsV2Response result = client.listObjectsV2(listObjectsV2Request);

			stream = new ByteArrayInputStream(result.toString().getBytes());
		} catch (Exception e) {
			throw new StorageException(e);
		}

		return stream;
	}
	
	@Override
	public void headObject(String container, String object, Config config ) {
		super.headObject(container, object, config);
		
		HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
				.bucket(container)
				.key(object)
				.build();
		
		try {
			client.headObject(headObjectRequest);
			
		} catch (Exception e) {
			throw new StorageException(e);
		}
	}
}
