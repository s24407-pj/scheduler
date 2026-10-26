package pl.kacosmetology.scheduler.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import pl.kacosmetology.scheduler.TestcontainersConfiguration

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration::class)
class CompanyCustomerRepositoryTest {

    @Autowired private lateinit var userRepository: CompanyCustomerRepository

    @Autowired private lateinit var companies: pl.kacosmetology.scheduler.company.CompanyRepository

    @Test
    fun `upsert retains one identity per company and allows the same phone in another company`() {
        val a = companies.saveAndFlush(pl.kacosmetology.scheduler.company.Company(name = "A")).id!!
        val b = companies.saveAndFlush(pl.kacosmetology.scheduler.company.Company(name = "B")).id!!
        val phone = "+48600100200"
        assertEquals(1, userRepository.insertIfPhoneAbsent(a, phone))
        val first = userRepository.findByCompanyIdAndPhoneNumber(a, phone)!!
        assertEquals(0, userRepository.insertIfPhoneAbsent(a, phone))
        assertEquals(first.id, userRepository.findByCompanyIdAndPhoneNumber(a, phone)!!.id)
        assertEquals(1, userRepository.insertIfPhoneAbsent(b, phone))
        org.junit.jupiter.api.Assertions.assertNotEquals(
            first.id,
            userRepository.findByCompanyIdAndPhoneNumber(b, phone)!!.id,
        )
    }
}
