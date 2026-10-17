package pl.kacosmetology.scheduler.config

import java.util.UUID
import org.hibernate.id.uuid.UuidVersion7Strategy

/** Generates RFC 9562 UUIDv7 identifiers for durable records created before persistence. */
object EntityIds {
    /** Uses the same Hibernate UUIDv7 strategy as ORM-generated entity identifiers. */
    fun next(): UUID = UuidVersion7Strategy.INSTANCE.generateUuid(null)
}
