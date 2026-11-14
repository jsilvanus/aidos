package dev.aidos.broker

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonSchemaValidatorTest {

    private val validator = JsonSchemaValidator()

    @Test
    fun `string primitives like 42, 3_14, true, null are treated as strings not numbers or booleans`() {
        val stringSchema = buildJsonObject { put("type", "string") }
        val intSchema = buildJsonObject { put("type", "integer") }
        val numSchema = buildJsonObject { put("type", "number") }
        val boolSchema = buildJsonObject { put("type", "boolean") }
        val nullSchema = buildJsonObject { put("type", "null") }

        assertTrue(validator.validate(JsonPrimitive("42"), stringSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive("3.14"), stringSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive("true"), stringSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive("null"), stringSchema).isSuccess)

        assertTrue(validator.validate(JsonPrimitive("42"), intSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive("42"), numSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive("3.14"), numSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive("true"), boolSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive("null"), nullSchema).isFailure)

        assertTrue(validator.validate(JsonPrimitive(42), stringSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive(true), stringSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive(42), intSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive(42), numSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive(3.14), numSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive(3.5), intSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive(true), boolSchema).isSuccess)
        assertTrue(validator.validate(JsonNull, nullSchema).isSuccess)
    }

    @Test
    fun `object and array schemas validate container types accurately`() {
        val objSchema = buildJsonObject {
            put("type", "object")
            put("required", buildJsonArray { add(JsonPrimitive("name")) })
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
            })
        }

        val valid = buildJsonObject {
            put("name", "aidos")
            put("tags", buildJsonArray { add(JsonPrimitive("kotlin")) })
        }
        assertTrue(validator.validate(valid, objSchema).isSuccess)
        assertTrue(validator.validate(buildJsonArray {}, objSchema).isFailure)

        val badItem = buildJsonObject {
            put("name", "aidos")
            put("tags", buildJsonArray { add(JsonPrimitive(123)) })
        }
        assertTrue(validator.validate(badItem, objSchema).isFailure)
    }

    @Test
    fun `integer validation supports 64-bit Long values and Long bounds`() {
        val boundedLongSchema = buildJsonObject {
            put("type", "integer")
            put("minimum", 4_000_000_000L)
            put("maximum", 6_000_000_000L)
        }

        assertTrue(validator.validate(JsonPrimitive(5_000_000_000L), boundedLongSchema).isSuccess)
        assertTrue(validator.validate(JsonPrimitive(3_000_000_000L), boundedLongSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive(7_000_000_000L), boundedLongSchema).isFailure)

        // Verify exact 64-bit precision above 2^53 (where Double loses least-significant bits).
        val precisionSchema = buildJsonObject {
            put("type", "integer")
            put("minimum", 9_007_199_254_740_994L)
        }
        assertTrue(validator.validate(JsonPrimitive(9_007_199_254_740_993L), precisionSchema).isFailure)
        assertTrue(validator.validate(JsonPrimitive(9_007_199_254_740_994L), precisionSchema).isSuccess)
    }

    @Test
    fun `string pattern mismatch fails validation instead of being swallowed`() {
        val patternSchema = buildJsonObject {
            put("type", "string")
            put("pattern", "^[a-z]+$")
        }

        assertTrue(validator.validate(JsonPrimitive("hello"), patternSchema).isSuccess)

        val mismatch = validator.validate(JsonPrimitive("hello123"), patternSchema)
        assertTrue(mismatch.isFailure, "regex mismatch must fail validation")
        val ex = assertIs<JsonSchemaValidator.ValidationException>(mismatch.exceptionOrNull())
        assertTrue(ex.message!!.contains("pattern"), "expected pattern error message, got: ${ex.message}")
    }

    @Test
    fun `invalid regex pattern in schema is skipped without crashing`() {
        val invalidPatternSchema = buildJsonObject {
            put("type", "string")
            put("pattern", "[unclosed")
        }

        assertTrue(validator.validate(JsonPrimitive("anything"), invalidPatternSchema).isSuccess)
    }
}
