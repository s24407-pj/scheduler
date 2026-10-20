package pl.kacosmetology.scheduler.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.BadCredentialsException
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.testId

class StaffCredentialTransactionsTest {
    private val accounts = mockk<CompanyEmployeeRepository>()
    private val sessions = mockk<AccountSessionRegistry>()
    private val transactions = StaffCredentialTransactions(accounts, sessions)

    @Test
    fun `six ID overflow never reads credentials or locks a partial set`() {
        every { accounts.findLoginCandidates("staff@salon.pl", match { it.pageSize == 6 }) } returns
            List(6) { testId(it.toLong() + 1) }
        assertEquals(emptyList<StaffCredentialSnapshot>(), transactions.snapshots("staff@salon.pl"))
        verify(exactly = 0) { accounts.findCredentialSnapshot(any()) }
        verify(exactly = 0) { accounts.findAccountForUpdate(any()) }
    }

    @Test
    fun `all matching account locks follow deterministic ID order`() {
        val accountsById =
            (1L..3L).map { id ->
                CompanyEmployee(
                    id = testId(id),
                    companyId = testId(id + 10),
                    userId = testId(id + 20),
                    role = "EMPLOYEE",
                    loginEmail = "staff@salon.pl",
                    passwordHash = "hash",
                )
            }
        accountsById.forEach { account -> every { accounts.findAccountForUpdate(account.id!!) } returns account }
        transactions.revalidate(accountsById.reversed().map(StaffCredentialSnapshot::from))
        verifyOrder {
            accounts.findAccountForUpdate(testId(1))
            accounts.findAccountForUpdate(testId(2))
            accounts.findAccountForUpdate(testId(3))
        }
    }

    @Test
    fun `identity company credential and eligibility changes are never substituted during revalidation`() {
        val original =
            CompanyEmployee(
                id = testId(1),
                companyId = testId(2),
                userId = testId(3),
                role = "OWNER",
                loginEmail = "staff@salon.pl",
                passwordHash = "hash",
            )
        val snapshot = StaffCredentialSnapshot.from(original)
        listOf(
                snapshot.copy(companyId = testId(9)),
                snapshot.copy(userId = testId(9)),
                snapshot.copy(email = "different@salon.pl"),
                snapshot.copy(passwordHash = "different"),
                snapshot.copy(enabled = false),
                snapshot.copy(role = "EMPLOYEE"),
                snapshot.copy(authVersion = 1),
            )
            .forEach { expected ->
                every { accounts.findAccountForUpdate(snapshot.id) } returns original
                assertThrows<BadCredentialsException> { transactions.revalidate(listOf(expected)) }
            }
    }
}
