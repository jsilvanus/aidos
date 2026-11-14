package dev.aidos.broker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * MVP JSON Schema validator for RFC-0030 Tool Broker (RFC-0008 Specification).
 *
 * Validates JSON objects against JSON Schema Draft 7 subset. Focuses on the most common
 * patterns used by Aidos tools:
 *  - type (object, string, number, integer, boolean, array, null)
 *  - required (array of property names)
 *  - properties (object of property schemas)
 *  - items (for arrays)
 *  - minimum, maximum (for numbers)
 *  - minLength, maxLength (for strings)
 *  - enum (array of allowed values)
 *  - pattern (regex, if environment supports it)
 *  - default (provides a default value)
 *
 * Not implemented: allOf, oneOf, anyOf, not, additionalProperties, format, etc.
 * These can be added later without breaking API compatibility.
 */
class JsonSchemaValidator {

    fun validate(value: JsonElement, schema: JsonObject): Result<Unit> {
        return try {
            validateInternal(value, schema)
            Result.success(Unit)
        } catch (e: ValidationException) {
            Result.failure(e)
        }
    }

    private fun validateInternal(value: JsonElement, schema: JsonObject) {
        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        // Check type first
        if (type != null) {
            if (type !in VALID_TYPES) {
                throw ValidationException("Unknown type: $type")
            }
            validateType(value, type)
        }

        // Type-specific validation
        when (type) {
            "string" -> validateString(value, schema)
            "number" -> validateNumber(value, schema)
            "integer" -> validateInteger(value, schema)
            "object" -> validateObject(value, schema)
            "array" -> validateArray(value, schema)
            "boolean" -> validateBoolean(value)
            "null" -> validateNull(value)
        }

        // Enum validation (applies to any type)
        (schema["enum"] as? JsonArray)?.let { enumValues ->
            if (!enumValues.any { enum -> enum == value }) {
                throw ValidationException("Value $value is not in enum: $enumValues")
            }
        }
    }

    private fun validateType(value: JsonElement, expectedType: String) {
        val actualType = when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonNull -> "null"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.longOrNull != null -> "integer"
                value.doubleOrNull?.takeIf { it.isFinite() } != null -> "number"
                else -> "invalid"
            }
        }

        // Allow "number" for integer values (JSON Schema permits this)
        if (expectedType == "number" && actualType == "integer") return
        if (expectedType == "integer" && actualType == "number") {
            val num = (value as JsonPrimitive).doubleOrNull
            if (num == null || !num.isFinite() || num % 1.0 != 0.0) {
                throw ValidationException("Expected integer but got decimal: $value")
            }
            return
        }

        if (actualType != expectedType) {
            throw ValidationException("Type mismatch: expected '$expectedType' but got '$actualType' for value '$value'")
        }
    }

    private fun validateString(value: JsonElement, schema: JsonObject) {
        if (value !is JsonPrimitive || !value.isString) return

        val str = value.content

        // minLength
        (schema["minLength"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { min ->
            if (str.length < min) {
                throw ValidationException("String length ${str.length} is less than minLength $min")
            }
        }

        // maxLength
        (schema["maxLength"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { max ->
            if (str.length > max) {
                throw ValidationException("String length ${str.length} exceeds maxLength $max")
            }
        }

        // pattern (simple regex check if supported)
        (schema["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { pattern ->
            val regex = try {
                Regex(pattern)
            } catch (_: Exception) {
                // Invalid regex in schema; skip pattern matching rather than failing all calls.
                null
            }
            if (regex != null && !str.matches(regex)) {
                throw ValidationException("String '$str' does not match pattern '$pattern'")
            }
        }
    }

    private fun validateNumber(value: JsonElement, schema: JsonObject) {
        if (value !is JsonPrimitive || value.isString) return
        val num = value.doubleOrNull?.takeIf { it.isFinite() } ?: return

        finiteDoubleBound(schema["minimum"])?.let { min ->
            if (num < min) {
                throw ValidationException("Number $num is less than minimum $min")
            }
        }

        finiteDoubleBound(schema["maximum"])?.let { max ->
            if (num > max) {
                throw ValidationException("Number $num exceeds maximum $max")
            }
        }

        finiteDoubleBound(schema["exclusiveMinimum"])?.let { min ->
            if (num <= min) {
                throw ValidationException("Number $num is not greater than exclusiveMinimum $min")
            }
        }

        finiteDoubleBound(schema["exclusiveMaximum"])?.let { max ->
            if (num >= max) {
                throw ValidationException("Number $num is not less than exclusiveMaximum $max")
            }
        }
    }

    private fun validateInteger(value: JsonElement, schema: JsonObject) {
        if (value !is JsonPrimitive || value.isString) return
        val longVal = value.longOrNull
        if (longVal != null) {
            checkLongBound(longVal, schema["minimum"], "is less than minimum", { v, b -> v < b }, { v, b -> v < b })
            checkLongBound(longVal, schema["maximum"], "exceeds maximum", { v, b -> v > b }, { v, b -> v > b })
            checkLongBound(longVal, schema["exclusiveMinimum"], "is not greater than exclusiveMinimum", { v, b -> v <= b }, { v, b -> v <= b })
            checkLongBound(longVal, schema["exclusiveMaximum"], "is not less than exclusiveMaximum", { v, b -> v >= b }, { v, b -> v >= b })
            return
        }

        val doubleVal = value.doubleOrNull?.takeIf { it.isFinite() } ?: return
        finiteDoubleBound(schema["minimum"])?.let { min ->
            if (doubleVal < min) {
                throw ValidationException("Integer $value is less than minimum $min")
            }
        }
        finiteDoubleBound(schema["maximum"])?.let { max ->
            if (doubleVal > max) {
                throw ValidationException("Integer $value exceeds maximum $max")
            }
        }
        finiteDoubleBound(schema["exclusiveMinimum"])?.let { min ->
            if (doubleVal <= min) {
                throw ValidationException("Integer $value is not greater than exclusiveMinimum $min")
            }
        }
        finiteDoubleBound(schema["exclusiveMaximum"])?.let { max ->
            if (doubleVal >= max) {
                throw ValidationException("Integer $value is not less than exclusiveMaximum $max")
            }
        }
    }

    private inline fun checkLongBound(
        value: Long,
        boundElement: JsonElement?,
        verb: String,
        longCompare: (Long, Long) -> Boolean,
        doubleCompare: (Double, Double) -> Boolean,
    ) {
        val prim = boundElement as? JsonPrimitive ?: return
        if (prim.isString) return
        val longBound = prim.longOrNull
        if (longBound != null) {
            if (longCompare(value, longBound)) {
                throw ValidationException("Integer $value $verb $longBound")
            }
            return
        }
        val doubleBound = prim.doubleOrNull?.takeIf { it.isFinite() } ?: return
        if (doubleCompare(value.toDouble(), doubleBound)) {
            throw ValidationException("Integer $value $verb $doubleBound")
        }
    }

    private fun finiteDoubleBound(boundElement: JsonElement?): Double? {
        val prim = boundElement as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return prim.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun validateObject(value: JsonElement, schema: JsonObject) {
        if (value !is JsonObject) return

        // Check required fields
        (schema["required"] as? JsonArray)?.forEach { reqField ->
            val fieldName = (reqField as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            if (!value.containsKey(fieldName)) {
                throw ValidationException("Required field '$fieldName' is missing")
            }
        }

        // Validate each property
        (schema["properties"] as? JsonObject)?.forEach { (propName, propSchema) ->
            value[propName]?.let { propValue ->
                if (propSchema is JsonObject) {
                    validateInternal(propValue, propSchema)
                }
            }
        }
    }

    private fun validateArray(value: JsonElement, schema: JsonObject) {
        if (value !is JsonArray) return

        (schema["minItems"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { min ->
            if (value.size < min) {
                throw ValidationException("Array size ${value.size} is less than minItems $min")
            }
        }

        (schema["maxItems"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.let { max ->
            if (value.size > max) {
                throw ValidationException("Array size ${value.size} exceeds maxItems $max")
            }
        }

        // Validate items
        schema["items"]?.let { itemSchema ->
            if (itemSchema is JsonObject) {
                value.forEach { item ->
                    validateInternal(item, itemSchema)
                }
            }
        }
    }

    private fun validateBoolean(value: JsonElement) {
        if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) {
            throw ValidationException("Expected boolean but got $value")
        }
    }

    private fun validateNull(value: JsonElement) {
        if (value !is JsonNull) {
            throw ValidationException("Expected null but got $value")
        }
    }

    class ValidationException(message: String) : Exception(message)

    private companion object {
        val VALID_TYPES = setOf("string", "number", "integer", "object", "array", "boolean", "null")
    }
}
