# RFC Process

All major design decisions in Aidos go through the RFC (Request for Comments) process. This ensures decisions are transparent, reviewed, and documented for future contributors.

## Status

Each RFC progresses through the following statuses:

- **Draft**: Initial proposal. Open for discussion and feedback.
- **Accepted**: The design has been approved and work may begin.
- **Implemented**: The feature or system described in the RFC has been built and merged.
- **Deprecated**: The system or design approach is no longer used or recommended.
- **Superseded**: A newer RFC has replaced this one.

## RFC Structure

Every RFC must contain the following sections:

### Abstract

A brief summary of the proposal (1–2 sentences).

### Motivation

Why this design decision is needed. What problem does it solve?

### Goals

Specific outcomes this RFC aims to achieve.

### Non-goals

What this RFC explicitly does NOT address.

### Design

High-level architecture and design approach. How does it work?

### Data Model

Structures, schemas, or storage patterns introduced by this design.

### Security

Security implications, threat model, and mitigations.

### MVP

Minimal viable product. What is the smallest useful implementation?

### Future Work

Enhancements or extensions possible after MVP.

## Numbering

RFCs are numbered in ranges by topic area:

- **0000–0009**: Vision and principles
- **0010–0019**: Core concepts (projects, sessions, graphs, resources)
- **0020–0029**: AI engine and model providers
- **0030–0039**: Tool broker and integrations
- **0040–0049**: Storage and export
- **0050–0059**: Platform frontends
- **0060–0069**: SDK and extensibility
- **0099**: Roadmap
- **0100–0199**: Reviews and meta-architecture

## Creating an RFC

1. Choose a number in the appropriate range.
2. Create a file: `XXXX-title.md` (e.g., `0001-principles.md`).
3. Use the template below.
4. Submit for review and discussion.
5. Iterate until accepted.
6. Update status to "Accepted" or "Implemented".
