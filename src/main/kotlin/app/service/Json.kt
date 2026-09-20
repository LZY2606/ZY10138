package app.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object Json {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    fun write(v: Any?): String = mapper.writeValueAsString(v)
    fun <T> read(s: String, clazz: Class<T>): T = mapper.readValue(s, clazz)
    inline fun <reified T> read(s: String): T = mapper.readValue(s, T::class.java)
}
