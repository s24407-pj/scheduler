package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/** JPA repository for [User] entities. */
@Repository
interface UserRepository : JpaRepository<User, UUID> {

    /** Finds a user by phone number. Used for SMS OTP authentication. */
    fun findByPhoneNumber(phoneNumber: String): User?

    /**
     * Creates a customer account unless one with [phoneNumber] already exists, without failing the surrounding
     * transaction on a concurrent insert. Returns 1 if a row was inserted, 0 if the phone number was taken.
     */
    @Modifying
    @Query(
        value =
            """
        INSERT INTO customer_accounts (phone_number)
        SELECT :phoneNumber WHERE :firstName IS NOT NULL AND :lastName IS NOT NULL
        ON CONFLICT (phone_number) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertIfPhoneAbsent(
        @Param("phoneNumber") phoneNumber: String,
        @Param("firstName") firstName: String,
        @Param("lastName") lastName: String,
    ): Int
}
