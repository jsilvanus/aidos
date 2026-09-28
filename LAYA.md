# Laya and Typed Decisions

## Purpose

This document records the architectural considerations for adding Laya and/or a generic typed-decision capability to Aidos.

This is a design note, not an implementation commitment.

## Why consider Laya?

Laya is a non-generative decision model. Its distinctive interface is not merely ordinary classification: it evaluates text state through typed questions and returns typed answers/probabilities.

The Laya decision primitives are:

- `choice` — choose among alternatives and return the distribution over choices;
- `noul` — answer a boolean question with a probability;
- `score` — produce an ordinal/graded decision and distribution.

This matters for Aidos because reducing Laya to `classify(text, labels)` would throw away part of its useful abstraction.

Aidos should not treat Laya as a special-purpose LLM.

The useful abstraction is:

    application
        |
        v
    Aidos Engine
        |
        +-- decisions
        |      |
        |      +-- choice
        |      +-- noul
        |      +-- score
        |      +-- Laya
        |
        +-- text generation
        +-- embeddings
        +-- vision
        +-- audio / STT
        |
        v
    inference backends

## Decision capability vs classification

A generic `CLASSIFICATION` capability is still useful, but it should not be the whole abstraction if Aidos is intended to expose Laya's capabilities.

A better conceptual Engine capability is a typed decision API:

    DecisionCapability
        |
        +-- ChoiceDecision
        +-- BooleanDecision
        +-- ScoreDecision

Ordinary classification can be represented as a `choice` decision. More expressive Laya tasks can use `noul` or `score` without forcing every application through a label-only API.

Possible conceptual contracts:

    DecisionBackend
        decide(DecisionRequest)
            -> DecisionResult

with typed requests/results corresponding to the supported decision primitives.

The exact kernel contract should follow Aidos' existing typed-output architecture. It should not expose Laya's internal question encoding directly.

## Architectural fit

RFC-0022 already defines Aidos as a general inference runtime rather than an LLM wrapper. It defines capability-specific backend interfaces, generic ONNX tensor inference, typed model outputs, and model/runtime lifecycle management.

Laya fits naturally above the generic ONNX backend:

    Laya semantic adapter
            |
            v
    Decision capability
       +----+----+
       |    |    |
     choice noul score
            |
            v
    ONNX Runtime backend
            |
            v
        ONNX model

The ONNX backend must remain completely unaware of Laya.

## Multiple typed decisions in one inference

One of Laya's useful properties is that several typed questions can be evaluated against the same input/state.

For example, Dictator could conceptually ask in one inference:

    intent: choice
        COMMAND
        LLM_REQUEST
        AMBIGUOUS

    explicit_action: noul
        "Is the user explicitly asking for an action?"

    destructive: noul
        "Would the requested action modify or delete content?"

    command_strength: score
        0..4

The Aidos API should be capable of representing such a set of typed decisions without making the application perform separate model invocations unnecessarily.

## Model adapter

A Laya adapter would own:

- Laya tokenizer/input preparation;
- Laya-specific question representation;
- ONNX input construction;
- output decoding;
- probability extraction;
- multiple-question batching;
- model-specific validation.

It should not own:

- model downloading;
- device-wide memory admission;
- backend selection;
- ONNX Runtime lifecycle;
- application routing policy.

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
    Laya adapter / decisions

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

Decision inference is attractive for routing because it is a one-shot model evaluation rather than token generation.

Potential uses include:

- command vs normal text;
- intent decisions;
- speech quality/noise decisions;
- caption boundary decisions;
- semantic routing;
- lightweight safety/policy decisions;
- filtering and reranking decisions.

Latency and memory should be measured on actual Aidos target platforms rather than inferred from desktop/GPU benchmarks.

## Multilingual support

The multilingual Laya model should be considered for Finnish and other supported languages.

Do not assume that English Laya performance transfers to Finnish. Aidos should have a small evaluation corpus before Laya becomes part of a latency- or safety-sensitive routing path.

Also do not assume that the general-purpose multilingual checkpoint is already good at Dictator-specific decisions. A domain-specific evaluation should precede any fine-tuning decision.

## Output model

Laya's typed results should become typed Aidos model outputs rather than leaking Laya-specific tensor details into applications.

For example:

    ChoiceDecision
        COMMAND       0.96
        LLM_REQUEST   0.03
        AMBIGUOUS     0.01

    BooleanDecision
        true          0.97

    ScoreDecision
        expected      3.8
        distribution  ...

The exact kernel representation should follow RFC-0022's generalized ModelResponse and typed-output architecture.

## `noul` considerations

`noul` is useful for explicit yes/no questions, but it should not automatically become the preferred primitive for every boolean decision. Its behaviour should be evaluated on Aidos' actual target data; where appropriate, a two-option `choice` can be an alternative representation.

## `score` considerations

`score` can represent graded or ordinal decisions that ordinary classification cannot express naturally. It may be useful for routing strength or confidence-like tasks, but should initially be treated as experimental and evaluated on domain data rather than assumed to be calibrated.

## Testing before adoption

A useful first experiment is a local/shadow evaluation:

1. Load Laya through Aidos.
2. Run Finnish and English examples.
3. Exercise `choice`, `noul`, and `score`.
4. Test multiple typed decisions in one inference.
5. Measure latency and memory.
6. Compare results with human labels.
7. Keep it out of critical routing until false-positive/false-negative behaviour is understood.
8. Only then consider fine-tuning or application-specific adapters.

## Important boundary

Laya should make decisions. It should not execute application actions.

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

- Should typed decisions be a first-class Engine capability in RFC-0022?
- What should the generic DecisionRequest/DecisionResult contracts contain?
- Should `choice`, `noul`, and `score` be explicit kernel types or adapter-level types?
- How should probability calibration be represented?
- How should multiple questions/results be represented and batched?
- Which Laya ONNX export should be supported?
- What Android/JVM execution providers are appropriate?
- How should model memory requirements participate in admission policy?
- Is a dedicated decision-model registry needed, or is the existing model registry sufficient?
- Should Aidos support Laya initially, or use a smaller decision model first as an architecture test?

## Current recommendation

Do not make Laya a special case in the Aidos Engine.

First establish a reusable typed-decision capability that can represent `choice`, `noul`, and `score`. Make ONNX Runtime capable of serving the underlying model. Then implement Laya as one model-specific adapter.

Ordinary classification should be a useful application of the decision capability, not the definition of the whole interface.
