package pl.kacosmetology.scheduler.config

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.treatment.ProvidedService
import pl.kacosmetology.scheduler.treatment.TreatmentRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

/** Seeds the database with sample data. Only active in the `dev` profile. */
@Component
@Profile("dev")
class DataInitializer(
    private val companyRepository: CompanyRepository,
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val serviceRepository: TreatmentRepository,
    private val passwordEncoder: PasswordEncoder
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(DataInitializer::class.java)

    override fun run(vararg args: String) {
        if (companyRepository.count() > 0L) return

        logger.info("Seeding test data (dev profile)...")

        val company = companyRepository.save(Company(name = "Barber Shop u Tomka"))

        val employee = userRepository.save(
            User(
                phoneNumber = "+48999888777",
                firstName = "Tomasz",
                lastName = "Mistrz",
                email = "tomek@barbershop.pl",
                passwordHash = passwordEncoder.encode("admin123")
            )
        )

        companyEmployeeRepository.save(
            CompanyEmployee(companyId = company.id!!, userId = employee.id, role = "OWNER")
        )

        serviceRepository.saveAll(
            listOf(
                ProvidedService(companyId = company.id!!, name = "Strzyżenie Męskie", durationMinutes = 30, price = 60),
                ProvidedService(
                    companyId = company.id!!,
                    name = "Strzyżenie + Broda",
                    durationMinutes = 60,
                    price = 100
                ),
                ProvidedService(companyId = company.id!!, name = "Tuszowanie siwizny", durationMinutes = 45, price = 80)
            )
        )

        logger.info("Test data seeded. Employee ID: ${employee.id}")
    }
}