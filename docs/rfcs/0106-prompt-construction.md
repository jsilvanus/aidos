# RFC-0106: Prompt Construction and Context Assembly

Status: Draft

## Abstract

This RFC defines how prompts and model context are assembled. It standardizes the inputs, precedence rules, budget constraints, privacy filtering, and provenance requirements for model requests.

## Motivation

The architecture requires AI reasoning but currently leaves prompt construction scattered across sessions and tools. This leads to inconsistent results, hidden prompt injection risk, and poor visibility into what context was sent to a model.

## Goals

1. Define the components of prompt construction.
2. Define precedence and conflict resolution among instructions, context, and user input.
3. Define token budgets and privacy filtering.
4. Define provenance and citation requirements.

## Non-goals

This RFC does not define the internal logic of any specific model provider.
It does not define training or fine-tuning strategies.

## Design

Prompt construction is a first-class subsystem. It consumes:

- intent graph state
- resource graph contents
- instructions and policies
- prior outputs and artifacts
- tool state and execution context
- user messages and attachments

It produces a structured prompt package with metadata: model selection, token budget, redaction plan, citations, and rationale trace.

## Data Model

PromptPackage {
  prompt_text,
  context_items,
  budget,
  redaction_rules,
  citations,
  model_preferences,
  provenance_ids
}

## Security

Prompt construction must prevent prompt injection from untrusted content and must ensure sensitive content is filtered or redacted before remote model calls.

## MVP

The MVP supports prompt assembly for single-turn and multi-turn sessions with basic provenance and budget management.

## Future Work

Future work includes richer prompt templates, multi-modal context, and adaptive context compression.
