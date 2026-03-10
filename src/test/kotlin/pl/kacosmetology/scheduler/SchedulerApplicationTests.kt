package pl.kacosmetology.scheduler

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import software.amazon.awssdk.services.s3.S3Client

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SchedulerApplicationTests {

    @MockkBean
    private lateinit var s3Client: S3Client

    @Test
    fun contextLoads() {
    }

}
