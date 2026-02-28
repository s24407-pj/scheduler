package pl.kacosmetology.scheduler.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** JPA repository for [User] entities. */
@Repository
interface UserRepository : JpaRepository<User, Long> {

    /** Finds a user by email address. Used for staff login and email uniqueness validation. */
    fun findByEmail(email: String): User?

    /** Finds a user by phone number. Used for SMS OTP authentication. */
    fun findByPhoneNumber(phoneNumber: String): User?
}