package dev.aidos.kernel

/** Identity of the model that produced a response. */
data class ModelRef(
    val id: String,
    val version: String,
)

/**
 * Provider/runtime usage information. Counts are optional because not every backend exposes
 * tokenization or meaningful token accounting.
 */
data class Usage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

/**
 * A typed result emitted by an inference backend.
 *
 * This is intentionally an open interface rather than a sealed hierarchy. New backend/module
 * output types can therefore be introduced without requiring a kernel release merely to extend
 * the output vocabulary.
 */
interface ModelOutput

data class TextOutput(val text: String) : ModelOutput

data class ToolCallOutput(val call: ToolCall) : ModelOutput

/** Generic named tensor output. The name corresponds to the backend/model output name. */
data class TensorOutput(
    val name: String,
    val tensor: Tensor,
) : ModelOutput

/**
 * Engine-neutral tensor representation. Storage is abstract so implementations can later use
 * native, mapped, shared, or device-backed memory without forcing a ByteArray copy.
 */
data class Tensor(
    val elementType: TensorElementType,
    val shape: List<Long>,
    val storage: TensorStorage,
    /** Strides in elements. Null means the backend uses its canonical contiguous layout. */
    val strides: List<Long>? = null,
)

enum class TensorElementType {
    BOOL,
    INT8,
    UINT8,
    INT16,
    UINT16,
    INT32,
    UINT32,
    INT64,
    UINT64,
    FLOAT16,
    FLOAT32,
    FLOAT64,
}

/** Tensor memory owned by an implementation. */
interface TensorStorage {
    val byteSize: Long
}

/** Simple common-memory storage for tests and backends that naturally expose byte arrays. */
data class ByteArrayTensorStorage(
    val bytes: ByteArray,
) : TensorStorage {
    override val byteSize: Long get() = bytes.size.toLong()
}
