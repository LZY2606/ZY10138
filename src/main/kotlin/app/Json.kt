package app

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 全局 JSON：Instant 输出 ISO-8601 UTC 字符串；段系数以 JSON 数组存库。 */
object Json {
    val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

    fun encodeCoeff(c: List<Double>): String = mapper.writeValueAsString(c)

    fun decodeCoeff(s: String): List<Double> =
        mapper.readValue(s, mapper.typeFactory.constructCollectionType(List::class.java, Double::class.java))
}
