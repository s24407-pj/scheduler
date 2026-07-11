package pl.kacosmetology.scheduler.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
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

    /** Creates an R2 client, using unsigned requests when credentials are not configured. */
    @Bean
    fun s3Client(r2Props: R2Properties): S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(r2Props.endpoint))
            .credentialsProvider(credentialsProvider(r2Props))
            .region(Region.of("auto"))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build()
            )
            .build()

    private fun credentialsProvider(r2Props: R2Properties): AwsCredentialsProvider =
        if (r2Props.accessKey.isBlank() && r2Props.secretKey.isBlank()) {
            AnonymousCredentialsProvider.create()
        } else {
            require(r2Props.accessKey.isNotBlank() && r2Props.secretKey.isNotBlank()) {
                "Both R2 access key and secret key must be configured"
            }
            StaticCredentialsProvider.create(AwsBasicCredentials.create(r2Props.accessKey, r2Props.secretKey))
        }
}
