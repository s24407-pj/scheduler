package pl.kacosmetology.scheduler.reservation

import java.time.LocalDateTime
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE

/** Real schema fixtures keep customer authentication and company staff identities independent. */
@Component
class ContractFixtures(private val jdbc: JdbcTemplate) {
    data class Salon(val company: UUID, val employee: UUID, val staff: UUID, val service: UUID, val customer: UUID)

    fun salon(): Salon {
        val company =
            jdbc.queryForObject(
                "INSERT INTO companies(name,address) VALUES('Salon','Warszawa') RETURNING id",
                UUID::class.java,
            )!!
        val employee = jdbc.queryForObject("SELECT uuidv7()", UUID::class.java)!!
        val staff =
            jdbc.queryForObject(
                "INSERT INTO company_employees(company_id,user_id,role,login_email,first_name,last_name) VALUES(?,?,'OWNER',?,'Anna','Pracownik') RETURNING id",
                UUID::class.java,
                company,
                employee,
                "${UUID.randomUUID()}@example.test",
            )!!
        val service =
            jdbc.queryForObject(
                "INSERT INTO offerings(company_id,name,duration_minutes,price) VALUES(?,'Strzyżenie',30,101) RETURNING id",
                UUID::class.java,
                company,
            )!!
        val customer =
            jdbc.queryForObject(
                "INSERT INTO customer_accounts(phone_number) VALUES(?) RETURNING id",
                UUID::class.java,
                "+48" + (100000000..999999999).random(),
            )!!
        return Salon(company, employee, staff, service, customer)
    }

    fun profile(s: Salon, customer: UUID = s.customer) {
        jdbc.update(
            "INSERT INTO company_customers(company_id,user_id,first_name,last_name) VALUES(?,?,'Jan','Klient') ON CONFLICT DO NOTHING",
            s.company,
            customer,
        )
    }

    fun shift(s: Salon, start: LocalDateTime) {
        jdbc.update(
            "INSERT INTO employee_work_schedules(company_id,employee_id,day_of_week,start_time,end_time) VALUES(?,?,?,'08:00','20:00') ON CONFLICT DO NOTHING",
            s.company,
            s.employee,
            start.dayOfWeek.name,
        )
    }

    fun reservation(
        s: Salon,
        start: LocalDateTime,
        status: String = "BOOKED",
        customer: UUID = s.customer,
        employee: UUID = s.employee,
    ): UUID {
        profile(s, customer)
        return jdbc.queryForObject(
            "INSERT INTO reservations(company_id,customer_id,employee_id,service_id,price,start_time,end_time,status,accepted_service_name,agreed_duration_minutes,creation_source) VALUES(?,?,?,?,101,?,?,?,'Strzyżenie',30,'CUSTOMER') RETURNING id",
            UUID::class.java,
            s.company,
            customer,
            employee,
            s.service,
            start.atZone(BUSINESS_ZONE).toOffsetDateTime(),
            start.plusMinutes(30).atZone(BUSINESS_ZONE).toOffsetDateTime(),
            status,
        )!!
    }
}
