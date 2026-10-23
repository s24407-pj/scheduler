package pl.kacosmetology.scheduler

import java.util.*

/** Stable UUIDv7 fixtures, independent of generated persistence IDs. */
fun testId(value: Long): UUID = UUID(0x7000L, Long.MIN_VALUE or value)
