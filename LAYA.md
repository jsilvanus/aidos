# Laya and Classification

## Purpose

This document records the architectural considerations for adding Laya and/or generic classification support to Aidos.

This is a design note, not an implementation commitment.

## Why consider Laya?

Laya is a non-generative decision/classification model. It can answer typed questions about text and return probabilities. The multilingual variant is especially relevant to Aidos because Aidos is intended to support local, capability-oriented AI rather than only LLM text generation.

Aidos should not treat Laya as a special-purpose LLM.

The useful abstraction is:

    application
        |
        v
    Aidos Engine
        |
        +-- classification
        |      |
        |      +-- Laya
        |
        +-- text generation
        +-- embeddings
        +-- vision
        +-- audio / STT
        |
        v
    inference backends

## Architectural fit

RFC-0022 already defines Aidos as a general inference runtime rather than an LLM wrapper. It defines capability-specific backend interfaces, generic ONNX tensor inference, typed model outputs, and model/runtime lifecycle management.

Laya fits naturally above the generic ONNX backend:

    Laya semantic adapter
            |
            v
    Classification capability
            |
            v
    ONNX Runtime backend
            |
            v
    ONNX model

The ONNX backend must remain completely unaware of Laya.

## Generic classification capability

Before adding Laya-specific APIs, consider adding a generic classification capability to the Engine.

Possible conceptual contract:

    ClassificationBackend
        classify(ClassificationRequest)
            -> ModelResponse

The kernel contract should describe the inference operation and typed outputs, not Laya's question format.

A Laya adapter can translate the generic request into Laya's native representation.

This keeps the architecture useful for other classifiers, intent models, rerankers, safety models, and small semantic models.

## Model adapter

A Laya adapter would own:

- Laya tokenizer/input preparation
- Laya-specific question representation
- ONNX input construction
- output decoding
- probability extraction
- multiple-question batching
- model-specific validation

It should not own:

- model downloading
- device-wide memory admission
- backend selection
- ONNX Runtime lifecycle
- application routing policy

Those remain Aidos Engine/ModelRuntime responsibilities.

## Model format and runtime

The preferred initial runtime is ONNX Runtime.

The generic flow should remain:

    model artifact
        |
        v
    ModelRuntime
        |
        v
    backend selection
        |
        v
    ONNX Runtime
        |
        v
    Laya adapter / classification

Do not introduce a Python Laya service or an HTTP sidecar merely to run the model.

## Resource considerations

The multilingual Laya model is substantially smaller than a typical LLM, but it is not free on mobile. Model loading should therefore participate in the existing Aidos resource/admission policy.

Possible policies:

- keep resident while voice interaction is active;
- load on demand;
- unload under memory pressure;
- allow device profiles to decide whether it is suitable.

Do not hard-code "Laya is always resident" into the Engine.

## Latency

Classification is attractive for routing because it is a one-shot decision rather than token generation.

Potential uses include:

- command vs normal text;
- intent classification;
- speech quality/noise classification;
- caption boundary decisions;
- semantic routing;
- lightweight safety or policy decisions;
- reranking or filtering.

Latency and memory should be measured on actual Aidos target platforms rather than inferred from desktop/GPU benchmarks.

## Multilingual support

The multilingual Laya model should be considered for Finnish and other supported languages.

Do not assume that English Laya performance transfers to Finnish. Aidos should have a small evaluation corpus before Laya becomes part of a latency- or safety-sensitive routing path.

## Output model

Laya's probabilities should become typed Aidos model outputs rather than leaking Laya-specific tensor details into applications.

For example:

    ClassificationResult
      labels
        COMMAND       0.96
        LLM_REQUEST   0.03
        AMBIGUOUS     0.01

The exact kernel representation should follow RFC-0022's generalized ModelResponse and typed-output architecture.

## Testing before adoption

A useful first experiment is a local/shadow evaluation:

1. Load Laya through Aidos.
2. Run Finnish and English examples.
3. Measure latency and memory.
4. Compare classification results with human labels.
5. Keep it out of critical routing until false-positive/false-negative behaviour is understood.
6. Only then consider fine-tuning or application-specific adapters.

## Important boundary

Laya should classify. It should not execute application actions.

For example:

    Laya -> "probably COMMAND"
                |
                v
        application parser
                |
        validated command
                |
                v
             execute

This preserves a deterministic authorization/execution boundary.

## Open questions

- Should classification be a first-class Engine capability in RFC-0022?
- Should ClassificationBackend be a kernel interface or initially an adapter over TensorBackend?
- What should the generic ClassificationRequest contain?
- How should probability calibration be represented?
- Should multi-question classification be exposed generically?
- Which Laya ONNX export should be supported?
- What Android/JVM execution providers are appropriate?
- How should model memory requirements participate in admission policy?
- Is a dedicated classifier model registry needed, or is the existing model registry sufficient?
- Should Aidos support Laya initially, or use a smaller classifier first as an architecture test?

## Current recommendation

Do not make Laya a special case in the Aidos Engine.

First establish a generic classification capability and make ONNX Runtime capable of serving it. Then implement Laya as one model-specific adapter.

That gives Aidos a reusable classification architecture even if Laya itself is later replaced.
