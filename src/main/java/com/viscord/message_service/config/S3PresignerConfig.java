package com.viscord.message_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
public class S3PresignerConfig {
    @Bean
    S3Presigner s3Presigner(StorageProperties props, AwsCredentialsProvider credentials, AwsRegionProvider region) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .credentialsProvider(credentials)
                .region(region.getRegion())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());

        if (props.publicEndpoint() != null) {
            builder.endpointOverride(props.publicEndpoint());
        }

        return builder.build();
    }
}
