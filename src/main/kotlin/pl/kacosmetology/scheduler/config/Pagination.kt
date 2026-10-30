package pl.kacosmetology.scheduler.config

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.data.domain.Sort
import org.springframework.http.ResponseEntity
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.security.ExpectedSessionContextFilter

/** Bounded slices avoid full-table counts; response arrays retain their existing JSON shape. */
fun pageRequest(page: Int, size: Int, sort: Sort = Sort.unsorted()): PageRequest {
    if (page < 0 || size !in 1..200 || page.toLong() * size > Int.MAX_VALUE)
        throw ApiException(ErrorCode.INVALID_PARAMETER, "Strona musi być nieujemna, a rozmiar od 1 do 200")
    return PageRequest.of(page, size, sort)
}

/** Continuation belongs to one immutable session scope; consumers must not combine pages from different accounts. */
fun <T : Any> Slice<T>.pageResponse(principal: CustomUserDetails): ResponseEntity<List<T>> =
    ResponseEntity.ok()
        .headers {
            it.set("X-Page-Context", ExpectedSessionContextFilter.context(principal))
            if (hasNext()) it.set("X-Next-Page", (number + 1).toString())
        }
        .body(content)
