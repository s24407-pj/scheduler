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
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingCategory
import pl.kacosmetology.scheduler.offering.OfferingCategoryRepository
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository

/** Seeds the database with sample data. Only active in the `dev` profile. Idempotent — safe to run on every startup. */
@Component
@Profile("dev")
class DataInitializer(
    private val companyRepository: CompanyRepository,
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val offeringRepository: OfferingRepository,
    private val offeringCategoryRepository: OfferingCategoryRepository,
    private val passwordEncoder: PasswordEncoder
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(DataInitializer::class.java)

    override fun run(vararg args: String) {
        val company = companyRepository.findAll().firstOrNull()
            ?: companyRepository.save(Company(name = "Ka.Cosmetology"))
        val cid = company.id!!

        ensureOwner(cid)
        ensureTestEmployee(cid)

        if (offeringRepository.count() == 0L) {
            seedOfferings(cid)
        }
    }

    private fun ensureOwner(companyId: Long) {
        if (userRepository.findByEmail("gabinet@kacosmetology.pl") != null) return

        logger.info("Seeding owner account...")
        val owner = userRepository.save(
            User(
                phoneNumber = "+48726154460",
                firstName = "Katarzyna",
                lastName = "Suwalska",
                email = "gabinet@kacosmetology.pl",
                passwordHash = passwordEncoder.encode("admin123")
            )
        )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = owner.id, role = "OWNER")
        )
        logger.info("Owner seeded: gabinet@kacosmetology.pl / admin123")
    }

    private fun ensureTestEmployee(companyId: Long) {
        if (userRepository.findByEmail("pracownik@kacosmetology.pl") != null) return

        logger.info("Seeding test employee account...")
        val employee = userRepository.save(
            User(
                phoneNumber = "+48100200300",
                firstName = "Jan",
                lastName = "Kowalski",
                email = "pracownik@kacosmetology.pl",
                passwordHash = passwordEncoder.encode("employee123")
            )
        )
        companyEmployeeRepository.save(
            CompanyEmployee(companyId = companyId, userId = employee.id, role = "EMPLOYEE")
        )
        logger.info("Test employee seeded: pracownik@kacosmetology.pl / employee123")
    }

    private fun seedOfferings(cid: Long) {
        logger.info("Seeding offerings...")

        val oprawaOka    = offeringCategoryRepository.save(OfferingCategory(companyId = cid, name = "Oprawa oka"))
        val trychologia  = offeringCategoryRepository.save(OfferingCategory(companyId = cid, name = "Trychologia"))
        val kosmetologia = offeringCategoryRepository.save(OfferingCategory(companyId = cid, name = "Kosmetologia"))
        val online       = offeringCategoryRepository.save(OfferingCategory(companyId = cid, name = "Online"))

        offeringRepository.saveAll(listOf(
            // Oprawa oka
            Offering(companyId = cid, name = "Henna brwi z regulacją",                           durationMinutes = 40,  price = 60,  categoryId = oprawaOka.id),
            Offering(companyId = cid, name = "Farbka z regulacją",                                durationMinutes = 40,  price = 80,  categoryId = oprawaOka.id),
            Offering(companyId = cid, name = "Regulacja brwi",                                    durationMinutes = 20,  price = 30,  categoryId = oprawaOka.id),
            Offering(companyId = cid, name = "Laminacja brwi + regulacja (bez koloryzacji)",      durationMinutes = 60,  price = 110, categoryId = oprawaOka.id),
            Offering(companyId = cid, name = "Laminacja brwi + regulacja + koloryzacja",          durationMinutes = 60,  price = 150, categoryId = oprawaOka.id),
            Offering(companyId = cid, name = "Lifting rzęs + farbka",                             durationMinutes = 90,  price = 150, categoryId = oprawaOka.id),
            Offering(companyId = cid, name = "Laminacja brwi + lifting rzęs",                     durationMinutes = 120, price = 250, categoryId = oprawaOka.id),
            // Trychologia
            Offering(companyId = cid, name = "Pierwsza konsultacja trychologiczna",               durationMinutes = 60,  price = 200, categoryId = trychologia.id),
            Offering(companyId = cid, name = "Zabieg trychologiczny dobrany indywidualnie",       durationMinutes = 90,  price = 350, categoryId = trychologia.id),
            Offering(companyId = cid, name = "Rekonstrukcja łodygi włosa – JOICO",                durationMinutes = 90,  price = 200, categoryId = trychologia.id),
            Offering(companyId = cid, name = "Mezoterapia mikroigłowa skóry głowy",               durationMinutes = 60,  price = 400, categoryId = trychologia.id),
            Offering(companyId = cid, name = "Mezoterapia igłowa skóry głowy",                    durationMinutes = 60,  price = 550, categoryId = trychologia.id),
            // Kosmetologia
            Offering(companyId = cid, name = "Oczyszczanie wodorowe",                             durationMinutes = 60,  price = 250, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Mezoterapia mikroigłowa + ampułka",                 durationMinutes = 120, price = 350, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Pierwsza konsultacja kosmetologiczna z zabiegiem",  durationMinutes = 120, price = 350, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Zabieg kosmetologiczny dobrany indywidualnie",      durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Eksfoliacja kwasami",                               durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Terapia trądziku – kolejny zabieg",                 durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Redukcja przebarwień",                              durationMinutes = 120, price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Zabieg regeneracyjny",                              durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Zabieg regeneracyjny dla kobiet w ciąży",           durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Działanie przeciwstarzeniowe",                      durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Zabieg nawilżający / odbudowujący",                 durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            Offering(companyId = cid, name = "Zabieg oczyszczający",                              durationMinutes = 90,  price = 300, categoryId = kosmetologia.id),
            // Online
            Offering(companyId = cid, name = "Konsultacja trychologiczna online",                 durationMinutes = 60,  price = 160, categoryId = online.id),
            Offering(companyId = cid, name = "Konsultacja kosmetologiczna online",                durationMinutes = 60,  price = 180, categoryId = online.id),
        ))

        logger.info("26 offerings seeded.")
    }
}
