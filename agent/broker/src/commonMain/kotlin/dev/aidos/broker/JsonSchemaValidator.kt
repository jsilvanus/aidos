package dev.aidos.broker

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val type = schema["type"]?.jsonPrimitive?.content

        // Check type first
        if (type != null) {
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
            else -> if (type != null) throw ValidationException("Unknown type: $type")
        }

        // Enum validation (applies to any type)
        schema["enum"]?.jsonArray?.let { enumValues ->
            if (!enumValues.any { enum -> enum == value }) {
                throw ValidationException("Value $value is not in enum: $enumValues")
            }
        }
    }

    private fun validateType(value: JsonElement, expectedType: String) {
        val actualType = when (value) {
            is JsonObject -> "object"
            is JsonNull -> "null"
            is JsonPrimitive -> {
                when {
                    value.booleanOrNull != null -> "boolean"
                    value.intOrNull != null -> "integer"
                    value.doubleOrNull != null -> "number"
                    else -> "string"
                }
            }
        }

        // Allow "number" for integer values (JSON Schema permits this)
        if (expectedType == "number" && actualType == "integer") return
        if (expectedType == "integer" && actualType == "number") {
            if ((value as JsonPrimitive).doubleOrNull?.let { it % 1 != 0.0 } == true) {
                throw ValidationException("Expected integer but got decimal: $value")
            }
            return
        }

        if (actualType != expectedType) {
            throw ValidationException("Type mismatch: expected '$expectedType' but got '$actualType' for value '$value'")
        }
    }

    private fun validateString(value: JsonElement, schema: JsonObject) {
        if (value !is JsonPrimitive || value.isString == false) return

        val str = value.content

        // minLength
        schema["minLength"]?.jsonPrimitive?.intOrNull?.let { min ->
            if (str.length < min) {
                throw ValidationException("String length ${str.length} is less than minLength $min")
            }
        }

        // maxLength
        schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { max ->
            if (str.length > max) {
                throw ValidationException("String length ${str.length} exceeds maxLength $max")
            }
        }

        // pattern (simple regex check if supported)
        schema["pattern"]?.jsonPrimitive?.content?.let { pattern ->
            try {
                if (!str.matches(Regex(pattern))) {
                    throw ValidationException("String '$str' does not match pattern '$pattern'")
                }
            } catch (e: Exception) {
                // If regex is invalid, skip validation (don't fail the entire call)
                if (e !is ValidationException) {
                    // Regex parsing failed, but that's a schema issue not a data issue
                }
            }
        }
    }

    private fun validateNumber(value: JsonElement, schema: JsonObject) {
        if (value !is JsonPrimitive) return
        val num = value.doubleOrNull ?: return

        schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { min ->
            if (num < min) {
                throw ValidationException("Number $num is less than minimum $min")
            }
        }

        schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { max ->
            if (num > max) {
                throw ValidationException("Number $num exceeds maximum $max")
            }
        }

        schema["exclusiveMinimum"]?.jsonPrimitive?.doubleOrNull?.let { min ->
            if (num <= min) {
                throw ValidationException("Number $num is not greater than exclusiveMinimum $min")
            }
        }

        schema["exclusiveMaximum"]?.jsonPrimitive?.doubleOrNull?.let { max ->
            if (num >= max) {
                throw ValidationException("Number $num is not less than exclusiveMaximum $max")
            }
        }
    }

    private fun validateInteger(value: JsonElement, schema: JsonObject) {
        if (value !is JsonPrimitive) return
        val num = value.intOrNull?.toDouble() ?: value.doubleOrNull
        if (num == null) return

        schema["minimum"]?.jsonPrimitive?.intOrNull?.toDouble()?.let { min ->
            if (num < min) {
                throw ValidationException("Integer $value is less than minimum $min")
            }
        }

        schema["maximum"]?.jsonPrimitive?.intOrNull?.toDouble()?.let { max ->
            if (num > max) {
                throw ValidationException("Integer $value exceeds maximum $max")
            }
        }
    }

    private fun validateObject(value: JsonElement, schema: JsonObject) {
        if (value !is JsonObject) return

        // Check required fields
        schema["required"]?.jsonArray?.forEach { reqField ->
            val fieldName = reqField.jsonPrimitive.content
            if (!value.containsKey(fieldName)) {
                throw ValidationException("Required field '$fieldName' is missing")
            }
        }

        // Validate each property
        schema["properties"]?.jsonObject?.forEach { (propName, propSchema) ->
            value[propName]?.let { propValue ->
                if (propSchema is JsonObject) {
                    validateInternal(propValue, propSchema)
                }
            }
        }
    }

    private fun validateArray(value: JsonElement, schema: JsonObject) {
        if (value !is kotlinx.serialization.json.JsonArray) return

        schema["minItems"]?.jsonPrimitive?.intOrNull?.let { min ->
            if (value.size < min) {
                throw ValidationException("Array size ${value.size} is less than minItems $min")
            }
        }

        schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { max ->
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
        if (value !is JsonPrimitive || value.booleanOrNull == null) {
            throw ValidationException("Expected boolean but got $value")
        }
    }

    private fun validateNull(value: JsonElement) {
        if (value !is JsonNull) {
            throw ValidationException("Expected null but got $value")
        }
    }

    class ValidationException(message: String) : Exception(message)
}
