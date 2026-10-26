package pl.kacosmetology.scheduler.user

/** Explicit international wire syntax only; never infers a country or rewrites legacy identity keys. */
const val INTERNATIONAL_PHONE_PATTERN = "^\\+[1-9][0-9]{8,14}$"

fun internationalPhone(value: String): String {
    require(value.matches(Regex(INTERNATIONAL_PHONE_PATTERN))) { "Podaj numer międzynarodowy, np. +48500123456" }
    return value
}
