package pl.kacosmetology.scheduler.config

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.time.LocalDateTime
import java.time.OffsetDateTime

/** Explicit instant conversion for Warsaw business local values, independent of the JVM/JDBC default timezone. */
@Converter(autoApply = true)
class BusinessDateTimeConverter : AttributeConverter<LocalDateTime, OffsetDateTime> {
    override fun convertToDatabaseColumn(value: LocalDateTime?): OffsetDateTime? =
        value?.atZone(BUSINESS_ZONE)?.toOffsetDateTime()

    override fun convertToEntityAttribute(value: OffsetDateTime?): LocalDateTime? =
        value?.atZoneSameInstant(BUSINESS_ZONE)?.toLocalDateTime()
}
