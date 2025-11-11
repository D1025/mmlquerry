package mag.mizarstack.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import java.net.URI;

@Configuration
public class S3Config {

    @Bean
    S3Client s3Client(
            @Value("${app.s3.endpoint}") String endpoint,
            @Value("${app.s3.region}") String region,
            @Value("${app.s3.accessKey}") String accessKey,
            @Value("${app.s3.secretKey}") String secretKey,
            @Value("${app.s3.pathStyle:true}") boolean pathStyle
    ) {
        var cfg = S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyle) // MinIO -> path-style
                .build();                          // patrz AWS SDK v2 docs
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .serviceConfiguration(cfg)
                .build();
    }
}
