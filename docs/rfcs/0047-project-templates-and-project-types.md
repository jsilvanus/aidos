# RFC-0047: Project Templates and Project Types

Status: Accepted 2026-08-04

## Abstract

A **project type** classifies what a project is for; a **template** is a starting point for one.
Together they are how a runtime built for coding serves research, planning, and life management
without special-casing any of them. This RFC also establishes that templates carry **no
authority and no executable content** — they are declarative starting material, nothing more.

## Motivation

Aidos claims to be a generic AI Operating Environment where coding is the first use case, not
the only one (RFC-0000). That claim is only true if a non-coding project is a first-class
configuration of the same runtime rather than a coding project with the code parts ignored.

Concretely, a research project and a coding project differ in what is worth indexing, which
tools matter, what the default instructions say, and what artifacts look like. Today those
differences would each be hard-coded somewhere or left to the user to assemble by hand.

There is also a trap worth naming early. Templates are exactly the kind of thing that
accumulates power: first defaults, then capabilities, then setup hooks, then arbitrary scripts.
At the end of that path, creating a project from a template downloaded from the internet is
running a program. **Templates are declarative and stay declarative**, and the reason is
recorded here so the pressure to add hooks meets a stated decision rather than an omission.

## Goals

1. Define project types and what they determine.
2. Define template contents and their hard limits.
3. Define instantiation.
4. Define versioning and how a project relates to a template after creation.
5. Define type changes.

## Non-goals

This RFC does not define a template marketplace or registry.
It does not define project creation UI.
It does not define the instruction format (RFC-0016).

## Design

### Project types

A project type is a stable identifier that selects defaults. Built-in types:

| Type | Indexes | Typical tools | Default instructions |
|---|---|---|---|
| `coding` | source, git history, symbols | fs, git, shell*, build | conventions, test-first, commit style |
| `research` | documents, notes, references | fs, git, web fetch* | citation discipline, source tracking |
| `writing` | prose, outlines, drafts | fs, git | voice, structure, revision practice |
| `planning` | notes, task lists | fs, git, calendar* | decomposition, review cadence |
| `personal` | notes, media, transcripts | fs, git, media | privacy-first defaults, no remote egress |
| `generic` | files | fs, git | none |

`*` = availability depends on platform profile (RFC-0049).

Types set **defaults**, never constraints. A `research` project can use the shell where it is
available; nothing is locked. This matters because real projects are hybrids — a research
project usually contains analysis code — and a type system that forbade that would be worse than
none.

Two type-specific defaults are worth calling out:

- `personal` defaults `routing.remote_egress = never`. A journal is the clearest case where the
  offline-first promise is the entire product, and the default should not require the user to
  discover the setting first.
- `coding` defaults `trust.untrusted_paths` to the dependency directories (RFC-0027), because
  reading a vendored README is the most likely injection vector in that domain.

### What a template contains

```kotlin
data class ProjectTemplate(
    val id: String,
    val version: SemVer,
    val projectType: ProjectType,
    val displayName: String,
    val description: String,

    val files: List<TemplateFile>,          // starter content, verbatim or with substitutions
    val settings: Map<String, JsonElement>, // PROJECT_SAFE / PREFERENCE settings only
    val instructions: List<TemplateFile>,   // AGENTS.md and similar
    val intentSeed: List<IntentSeedNode>,   // starting goals; requires RFC-0012, built last
    val gitignore: List<String>,
    val requirements: Requirements          // declared tool needs (RFC-0049)
)
```

### What a template cannot contain

This list is the substance of the RFC:

| Not permitted | Why |
|---|---|
| Capability grants | authority comes from the user, never from a file (RFC-0018) |
| Secrets or `secret_ref`s | credentials are user scope (RFC-0035) |
| `SECURITY` or `SPEND` settings | a template could otherwise disable egress controls (RFC-0036) |
| MCP server definitions | a `command` string makes instantiation code execution (RFC-0031) |
| Setup hooks or scripts | the same, more directly |
| Executable files | ditto |
| Absolute paths | escapes the project directory |

Instantiating a template writes files, sets `PROJECT_SAFE` settings, and stops. If a template
appears to need a hook, the correct answer is a first-run *session* — the user sees the proposed
work and approves it — not a script that runs before anyone is watching.

Templates carry a `requirements` block, which is advisory only (RFC-0049): declaring
`tools = ["shell"]` reports a degraded capability on a phone; it does not grant one.

### Instantiation

```
create project from template T:
  1. validate T against the prohibition list        ← fails closed
  2. create project directory, git init, .aidos/
  3. write files with substitutions ({{project_name}}, {{date}}, {{author}})
  4. write instruction files
  5. apply PROJECT_SAFE settings to aidos.toml
  6. seed intent nodes, if any
  7. evaluate requirements against the platform profile and report
  8. initial commit
```

Substitution is a fixed, closed set of placeholders — not a template language. A template
language is a program, and step 1 exists to keep programs out.

Everything a template writes is ordinary project content afterwards. There is no live link: a
project does not track its template and cannot be "re-applied" from one. The template's identity
and version are recorded in `aidos.toml` for provenance, and that is all.

That is deliberate. Live template inheritance means an upstream change can modify a user's
project, which reintroduces every problem this RFC exists to prevent.

### Built-in and user templates

**Built-in templates ship with the runtime**, are covered by its tests, and are the only ones
available in MVP.

**User templates** are created from an existing project (`aidos template export`) and stored at
user scope (RFC-0054). They are files the user wrote, on their own machine.

**Third-party templates are not supported in v1.** Not because the format is dangerous — the
prohibition list makes it inert — but because distribution needs a trust story, and MCP is
already the v1 extension boundary (RFC-0043). One unproven extension mechanism at a time.

### Type changes

A project's type is metadata and can be changed at any time. Changing it:

- updates future defaults for anything not explicitly set;
- does **not** rewrite existing settings, files, or instructions;
- is recorded in the audit log.

Projects genuinely change character — a research project grows a codebase — and a type that
could not be changed would simply be ignored.

## Data Model

```toml
# aidos.toml — recorded at creation, provenance only
[project]
type            = "coding"
template        = "coding/kotlin-library"
template_version = "1.2.0"
```

```
~/.aidos/templates/<id>/
├── template.toml         manifest
├── files/                starter content
└── instructions/         AGENTS.md and similar
```

The one database column this RFC owns is `projects.project_type` (`schema/project.sql`,
defaulting to `'generic'`). Templates themselves have **no SQLite tables**: they are files, and a
project's relationship to one is three lines of metadata.

## Security

The threat is *"cloning or creating a project runs something"* (RFC-0003, Threat 2). Templates
are the most natural place for that to creep in, so:

1. **Validation is fail-closed.** A template containing anything on the prohibition list is
   rejected with a specific reason, not sanitized and used.
2. **No executable content, ever.** Not scripts, not hooks, not a template language.
3. **No authority.** Grants, secrets, MCP definitions, and `SECURITY`/`SPEND` settings are
   rejected.
4. **Path containment.** All template paths are `RelPath` (RFC-0018) resolved through a handle;
   absolute paths and traversal are rejected at parse.
5. **Template content is `PROJECT` trust** (RFC-0027) — the same as any other file the user
   accepted into their project — and its instruction files participate in the normal precedence
   rules (RFC-0025) rather than gaining special authority.

## MVP

**Project types are in the MVP. Templates are not.** The two are separable and only the first is
exercised by the thesis, which is *opening a real repository* rather than creating one from a
template.

1. Project types `coding`, `research`, `writing`, `personal`, and `generic`, with their defaults
   (**M2**). `personal` defaulting `routing.remote_egress = never` and `coding` defaulting
   `trust.untrusted_paths` are security defaults worth having on day one — a journal is the
   clearest case where offline-first *is* the product, and the default should not wait for the
   user to discover a setting.
2. `projects.project_type` recorded and changeable; types set defaults, never constraints.

Not in MVP: **built-in templates, instantiation, substitution, and prohibition-list validation** —
the whole template mechanism. Also out: user template export, third-party templates, requirements
evaluation at creation, and **intent seeding of any kind**, since the Intent Graph is built last
(D20, M32c) and a template cannot seed what does not exist yet.

**The prohibition list stays normative even though nothing enforces it yet.** It is the reason
this RFC exists, and it must be true of the first template ever written rather than discovered
afterwards — a template that can carry a hook makes creating a project equivalent to running one
(RFC-0003, Threat 2).

## Future Work

`aidos template export` from an existing project, which is the natural way for real templates to
appear — they get extracted from something that worked, not authored in the abstract.

Type-specific frontend surfaces: a journal does not want a diff viewer, and a codebase does not
want a mood tracker.

Distribution, if and only if the plugin trust story lands first (RFC-0043).
