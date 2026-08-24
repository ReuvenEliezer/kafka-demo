package com.reuven.kafka.demo.services;

import com.reuven.kafka.demo.entities.MyEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

@Service
public class S3EventArchiveService {

    private static final Logger logger = LogManager.getLogger(S3EventArchiveService.class);

    private final S3Client s3Client;
    private final String bucket;

    public S3EventArchiveService(S3Client s3Client, @Value("${aws.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @CircuitBreaker(name = "s3Upload", fallbackMethod = "onUploadUnavailable")
    public void archive(MyEvent event) {
        byte[] body = ("id=%d,msg=%s".formatted(event.id(), event.msg())).getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(event.id() + ".json").build(),
                RequestBody.fromBytes(body));
    }

    private void onUploadUnavailable(MyEvent event, Throwable t) {
        logger.error("S3 archive call failed for event id={}: {}", event.id(), t.toString());
        throw new S3ArchiveUnavailableException(event.id(), t);
    }

}
