package pl.kacosmetology.scheduler.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/** JPA repository for [User] entities. */
@Repository
interface UserRepository : JpaRepository<User, Long> {

    /** Finds a user by email address. Used for staff login and email uniqueness validation. */
    fun findByEmail(email: String): User?

    /** Finds a user by phone number. Used for SMS OTP authentication. */
    fun findByPhoneNumber(phoneNumber: String): User?

    /**
     * Creates a customer account unless one with [phoneNumber] already exists, without failing the surrounding
     * transaction on a concurrent insert. Returns 1 if a row was inserted, 0 if the phone number was taken.
     */
    @Modifying
    @Query(
        value = """
        INSERT INTO users (phone_number, first_name, last_name)
        VALUES (:phoneNumber, :firstName, :lastName)
        ON CONFLICT (phone_number) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfPhoneAbsent(
        @Param("phoneNumber") phoneNumber: String,
        @Param("firstName") firstName: String,
        @Param("lastName") lastName: String
    ): Int
}