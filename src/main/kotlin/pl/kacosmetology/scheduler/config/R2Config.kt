package pl.kacosmetology.scheduler.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/** Configuration properties for Cloudflare R2 (S3-compatible object storage). */
@ConfigurationProperties(prefix = "r2")
data class R2Properties(
    /** Full endpoint URL, e.g. `https://{account-id}.r2.cloudflarestorage.com`. */
    val endpoint: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val bucketName: String = "",
    /** Base URL for publicly accessible objects, e.g. `https://pub-xxx.r2.dev`. */
    val publicUrl: String = ""
)

/** Registers a pre-configured [S3Client] pointing at the R2 endpoint. */
@Configuration
@EnableConfigurationProperties(R2Properties::class)
class R2Config {

    @Bean
    fun s3Client(r2Props: R2Properties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(r2Props.endpoint))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(r2Props.accessKey, r2Props.secretKey)
                )
            )
            .region(Region.of("auto"))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build()
            )
            .build()
}
