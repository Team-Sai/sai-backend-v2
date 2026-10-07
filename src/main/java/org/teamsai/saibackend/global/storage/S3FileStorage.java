package org.teamsai.saibackend.global.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * file.storage=s3 일 때만 만들어진다.
 * 접근 키를 코드나 설정에 넣지 않는다. EKS에서는 Pod에 연결된 IAM 역할(Pod Identity)을,
 * 노트북에서는 AWS CLI 로그인 정보를 SDK가 자동으로 찾는다.
 */
@Component
@ConditionalOnProperty(name = "file.storage", havingValue = "s3")
public class S3FileStorage {

    private final S3Client s3Client;
    private final String bucket;

    public S3FileStorage(
            @Value("${file.s3.bucket}") String bucket,
            @Value("${file.s3.region:ap-northeast-2}") String region
    ) {
        this.bucket = bucket;
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .build();
    }

    public void save(String key, InputStream content, long size, String contentType) throws IOException {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (SdkException e) {
            throw new IOException("S3 파일 저장 실패: " + key, e);
        }
    }

    public byte[] read(String key) throws IOException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (SdkException e) {
            throw new IOException("S3 파일 읽기 실패: " + key, e);
        }
    }
}