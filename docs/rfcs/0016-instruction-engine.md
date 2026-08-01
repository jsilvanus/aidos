# RFC-0016: Instruction Engine

Status: Accepted

## Abstract

The Instruction Engine discovers, parses, and merges instructions from multiple sources into a unified, deduplicated instruction set. It supports existing ecosystem files (AGENTS.md, CLAUDE.md, GEMINI.md, .cursor/rules, GitHub Copilot instructions) and is extensible to future formats via plugins. The Instruction Engine normalizes diverse instruction formats into a common representation, resolves conflicts, and delivers relevant instructions to sessions at runtime. Instructions are the bridge between human intent and AI behavior within the Aidos platform.

## Motivation

AI systems benefit from explicit, human-written instructions. As the ecosystem of AI tools has evolved, many formats have emerged:

- **AGENTS.md**: Task-specific instructions (by Anthropic).
- **CLAUDE.md**: Instructions for Claude (by Anthropic).
- **GEMINI.md**: Instructions for Gemini (by Google).
- **.cursor/rules**: Instructions for Cursor IDE.
- **.github/copilot-instructions.md**: Instructions for GitHub Copilot.
- **README**: Project-level conventions and guidelines.
- **Contributing.md**: Contribution guidelines.
- **Style guides**: Code style and conventions.

A project may have instructions scattered across multiple files and formats. An AI session should ideally receive:

1. **Unified instructions**: All applicable instructions, deduplicated and merged.
2. **Relevant instructions**: Only instructions relevant to the current task.
3. **Prioritized instructions**: Clear precedence when instructions conflict.
4. **Normalization**: Diverse formats converted to a common model.

The Instruction Engine solves this by discovering, parsing, normalizing, and delivering instructions.

Inspiration sources:

- **Configuration management** (merge multiple config files).
- **Cascading style sheets** (CSS) (specificity and precedence).
- **Build systems** (inheritance and override).
- **Unix philosophy** (small, composable pieces).

## Goals

1. **Define instruction semantics**: What is an instruction? How is it different from resources or configuration?

2. **Specify supported formats**: What instruction file formats does Aidos recognize?

3. **Establish discovery mechanism**: How does the engine find instruction sources?

4. **Define normalization**: How are different formats converted to a common representation?

5. **Clarify precedence and conflict resolution**: When instructions conflict, which one wins?

6. **Specify filtering and delivery**: How are relevant instructions selected for a session?

7. **Establish extensibility**: How can future formats be added without modifying the runtime?

## Non-goals

This RFC does not specify the exact format of normalized instructions. The model is what matters; encoding varies.

This RFC does not mandate enforcement of instructions (e.g., rejecting code that violates style guidelines). Instructions are advisory, not enforced.

This RFC does not address instruction versioning. Instructions evolve with resources; basic Git versioning is sufficient.

This RFC does not specify how instruction conflicts are detected or reported to users (that is UI).

This RFC does not address instruction performance (e.g., how to efficiently filter large instruction sets).

## Design

### What Is an Instruction?

An **Instruction** is a discrete, actionable directive that guides AI behavior. Instructions can be:

- **Stylistic**: "Use snake_case for variable names".
- **Architectural**: "Always use dependency injection".
- **Behavioral**: "Ask for confirmation before deleting anything".
- **Technical**: "Write tests first (TDD)".
- **Organizational**: "Add TODO comments for incomplete work".
- **Security**: "Never hardcode credentials".
- **Performance**: "Optimize queries for sub-1s response times".

Instructions are different from:

- **Resources** (RFC-0013): Resources are mutable knowledge documents. Instructions are discrete directives.
- **Configuration**: Configuration is system settings. Instructions are behavior guidelines.
- **Intent Graph** (RFC-0012): Intent is goals. Instructions are how to achieve them.

### Instruction Formats (Supported Sources)

The Instruction Engine recognizes several instruction file formats:

#### AGENTS.md

Format from Anthropic. Markdown file at root or docs/:

```markdown
# AGENTS.md

<task-name>
<description of what this agent should do>
<one instruction per line>
</task-name>
```

Example:

```markdown
# AGENTS.md

testing
Write unit tests for this module. Use pytest.
Tests should have > 80% coverage.
Mocking should simulate failures to test error paths.

documentation
Write API documentation in OpenAPI format.
Include examples for each endpoint.
Explain error codes and recovery strategies.
```

#### CLAUDE.md

Format for Claude instructions (Anthropic). Markdown at root:

```markdown
# CLAUDE.md

## General Guidelines
- Use snake_case for variables
- Write comments for complex logic
- Prefer explicit over implicit

## Architecture
- Follow dependency injection pattern
- Decouple I/O from business logic

## Testing
- Write tests before code
- Aim for 90%+ coverage
```

#### GEMINI.md

Similar to CLAUDE.md but for Gemini. Aidos treats these identically.

#### .cursor/rules

JSON or YAML configuration for Cursor IDE:

```json
{
  "rules": [
    "Use TypeScript strict mode",
    "All functions must have JSDoc comments",
    "Maximum line length: 100 characters"
  ]
}
```

#### .github/copilot-instructions.md

GitHub Copilot instructions format (Markdown):

```markdown
# Copilot Instructions

- Use modern ES6+ syntax
- Prefer async/await over promises
- Add error handling for all network requests
- Include unit tests
```

#### .gitignore and Other Artifacts

While not explicit instruction files, implicit instructions can be derived:

- **.gitignore**: "Do not commit these files" (implicit instruction to be careful with these).
- **Makefile**: Common build targets encode development conventions.
- **Package.json scripts**: Development and build procedures.
- **Test patterns**: Test file naming and structure encode testing conventions.

Future versions might extract instructions from these.

### Instruction Normalization

The Instruction Engine parses diverse formats and normalizes to a common model:

```
Instruction {
  id: UUID                          # Unique ID
  source_file: String               # e.g., "CLAUDE.md", ".cursor/rules"
  source_format: String             # e.g., "markdown", "json", "yaml"
  
  category: String                  # "style", "architecture", "testing", "security", etc.
  priority: Int                     # 1 (highest) to 10 (lowest)
  
  text: String                      # The instruction itself (normalized)
  
  applies_to: List<String>?         # Optional: applies to specific contexts
                                    # e.g., ["backend", "Python", "api"]
  
  conflicting_with: List<UUID>?     # Other instructions this conflicts with
  
  source_order: Int                 # Original order in source file
  
  created_at: Timestamp
  discovered_at: Timestamp
}
```

Examples of normalized instructions:

```
1. Instruction { id: ..., category: "style", text: "Use snake_case for variable names", priority: 5 }
2. Instruction { id: ..., category: "testing", text: "Write tests first (TDD)", priority: 5, applies_to: ["backend"] }
3. Instruction { id: ..., category: "security", text: "Never hardcode credentials", priority: 1 }
4. Instruction { id: ..., category: "documentation", text: "Write OpenAPI specs for APIs", priority: 5 }
```

### Discovery Process

The Instruction Engine discovers instruction sources:

```
Scan project root:
  Look for:
    - AGENTS.md
    - CLAUDE.md
    - GEMINI.md
    - .cursor/rules
    - .github/copilot-instructions.md
  For each found:
    Parse
    Normalize
    Store

Scan project directories:
  Recursively look for instruction files in docs/, .github/, etc.
  
Monitor for changes:
  Subscribe to filesystem events
  Re-scan if instruction files are modified
```

Discovery yields a collection of instruction sources, each of which is parsed and normalized.

### Precedence and Conflict Resolution

When multiple instructions apply to the same situation, precedence is determined by:

1. **Priority**: Instructions have explicit priority (1 = highest, 10 = lowest).
2. **Specificity**: More specific instructions (with applies_to constraints) override general ones.
3. **Source order**: If two instructions have same priority and specificity, the one appearing first in source wins.
4. **Project instructions**: Project-level instructions (AGENTS.md in project root) override external instructions.

Example conflict resolution:

```
Instruction A (from CLAUDE.md): "Use PascalCase for class names"
Priority: 5, applies_to: []

Instruction B (from project-level AGENTS.md): "Use snake_case for all names"
Priority: 3, applies_to: ["python"]

Current session task: Write Python classes

Resolution: Instruction B wins (higher priority in Python context)
→ Output: "Use snake_case for all names"
```

### Filtering and Delivery

At runtime, when a session is created or needs instructions, the Instruction Engine filters and delivers relevant instructions:

```
Filtering criteria:
  - Session role (driver, worker)
  - Task type (coding, testing, documentation, etc.)
  - Language/technology context (Python, Rust, JavaScript, etc.)
  - Module/area (backend, frontend, api, etc.)

Example:
  Session S1: role=driver, task="write Python API"
  → Filter instructions with applies_to: ["api", "python", "backend"]
  → Apply precedence rules
  → Deliver unified instruction set
```

The delivered instruction set is the "instruction context" provided to the session and/or AI model.

### Instruction Representation in Practice

Delivered instructions might look like:

```
# Merged Instructions for Backend API Development

## Style and Naming
- Use snake_case for variables and functions
- Use PascalCase for classes and types
- Max line length: 100 characters
- 2-space indentation

## Architecture
- All database access goes through repository layer
- Use dependency injection
- Separate I/O from business logic
- Minimize circular dependencies

## Testing
- Write tests first (TDD)
- Minimum coverage: 90% for core modules
- Use pytest and pytest-mock
- Test error paths, not just happy paths

## Documentation
- Write docstrings for all public functions
- Include type hints for parameters and return values
- Write README for new modules

## Security
- Never hardcode credentials
- All user input must be validated
- SQL queries must use parameterized queries
- Log security events

## Performance
- API responses must be < 1 second
- Database queries should be indexed
- Implement caching for frequently accessed data
```

This merged set is provided to sessions and can be included in AI prompts.

### Instruction Updates

Instruction sources can change:

```
User modifies CLAUDE.md
Instruction Engine detects change
Re-parses modified file
Regenerates merged instruction set
Updates delivery to sessions
```

Sessions already running use previous instruction set (for consistency). New sessions use updated set.

### Extensibility

The Instruction Engine is designed to support future formats via a provider model:

```
InstructionProvider {
  supported_files: List<String>     # e.g., [".cursor/rules", "Cursor.md"]
  parse(file_path: String) -> List<Instruction>
}

Built-in providers (MVP):
  - AgentsMdProvider
  - ClaudeMdProvider
  - CursorRulesProvider
  - CopilotInstructionsProvider

Future providers (plugins):
  - LlmRulesProvider (custom format for LLMs)
  - CustomDslProvider (domain-specific instruction language)
```

New providers can be added without modifying the runtime.

## Data Model (Conceptual)

```
InstructionSet {
  project_id: UUID
  
  sources: Map<String, InstructionSource>  # File → parsed source
  instructions: List<Instruction>          # All discovered instructions
  
  derived_sets: Map<Context, List<Instruction>>
                                            # Cached filtered sets by context
  
  last_discovery: Timestamp
  is_current: Boolean
}

InstructionSource {
  file_path: String                 # e.g., "AGENTS.md", ".cursor/rules"
  format: String                    # e.g., "markdown", "json"
  provider: String                  # e.g., "AgentsMdProvider"
  
  parsed_instructions: List<Instruction>
  raw_content: String               # For debugging
  
  discovered_at: Timestamp
  parsed_at: Timestamp
  is_valid: Boolean
  parse_errors: List<String>?
}

Instruction {
  id: UUID
  source_id: String                 # Which source this came from
  
  category: String                  # "style", "architecture", "testing", etc.
  priority: Int                     # 1 (highest) to 10 (lowest)
  
  text: String                      # The instruction
  applies_to: List<String>?         # Contexts where this applies
  
  conflicting_with: List<UUID>?     # Other instructions this conflicts with
  
  metadata: Map<String, Any>?
}
```

## Lifecycle

### Project Creation

When a project is created:

```
Scan for instruction sources
Parse each source
Normalize instructions
Build instruction set
Available for sessions
```

### Project Operation

As sessions run:

```
Session requests instructions
Filter by context (task type, language, etc.)
Apply precedence rules
Deliver merged instruction set
```

### Maintenance

Instruction sources evolve:

```
User modifies CLAUDE.md
Instruction Engine detects change
Re-parses and regenerates instruction set
Future sessions use updated set
```

## Examples

### Example 1: Merging Formats

Project has multiple instruction sources:

```
AGENTS.md:
  coding: Use TDD, 90%+ coverage, all public functions documented

CLAUDE.md:
  - Use snake_case
  - Minimize cyclomatic complexity

.cursor/rules:
  - Use TypeScript strict mode
  - Prefer async/await

Result (merged and deduplicated):
  1. Use test-driven development (TDD)
  2. Maintain 90%+ test coverage
  3. Document all public functions
  4. Use snake_case for naming
  5. Keep cyclomatic complexity low
  6. Use TypeScript strict mode
  7. Use async/await over promises
```

### Example 2: Conflict Resolution

Project has conflicting instructions:

```
Global (CLAUDE.md, priority 5):
  "Use comments liberally"

API module (docs/api/AGENTS.md, priority 2, applies_to: ["api"]):
  "Comments should be minimal; code should be self-documenting"

Session creating API code:

Engine resolves:
  Priority 2 (higher) > Priority 5
  Applies_to: ["api"] matches session context
  → Deliver: "Comments should be minimal"
```

### Example 3: Context-Aware Delivery

Project has many instruction files. Session S requests instructions:

```
Session S: role=worker, task="write tests", language="Python", context="payment_module"

Instruction Engine filters:
  1. Start with all discovered instructions
  2. Keep only those applicable to testing
  3. Keep only Python-specific instructions
  4. Keep only ones applicable to payment module
  5. Apply precedence rules
  
Deliver (example):
  - Use pytest framework
  - Test error paths, not just happy paths
  - Minimum coverage 95% for payment code (more strict)
  - Use pytest-mock for mocking
  - Payment-specific test patterns (from AGENTS.md)
```

## Security Considerations

### Instruction Injection

Instructions come from files in the project. Malicious instructions in these files could guide AI behavior unsafely. Mitigation:

- Instructions are visible to the user (not hidden).
- User can review instruction sources.
- Instruction conflicts are reported (user can resolve).

### Sensitive Instructions

Instructions should not contain secrets. If instructions reference secrets (e.g., "use the AWS_KEY environment variable"), the secret itself should not appear.

### Instruction Precedence

Precedence rules are explicit and auditable. Users can see which instruction "won" and why.

## MVP Scope

The MVP Instruction Engine includes:

1. **Format support**: AGENTS.md, CLAUDE.md, .cursor/rules.
2. **Discovery**: Scan project for instruction files.
3. **Parsing**: Parse each format correctly.
4. **Normalization**: Convert to common instruction model.
5. **Precedence**: Simple priority-based conflict resolution.
6. **Delivery**: Filter and deliver relevant instructions to sessions.
7. **Monitoring**: Re-scan when instruction files change.

The MVP does not include:

- Complex conflict detection/reporting (future).
- Instruction approval workflows (future).
- Embedded instruction editing in UI (future).
- Automatic instruction generation (future).
- Custom provider plugins (future, but architecture supports it).

## Future Work

### Visual Instruction Editing

UI for creating and editing instructions without manually editing files:

```
"Create instruction: Use snake_case"
→ Added to CLAUDE.md
→ Takes effect immediately
```

### Instruction Graph

Model instructions as a graph with relationships:

```
Instruction A: "Use dependency injection"
  Justification: "Enables testing"
  Related to: Instruction B: "Write tests first"
  Conflicts with: Instruction C: "Minimal abstraction layers"
```

### Automatic Generation

Auto-generate AGENTS.md from project patterns:

```
Analyze codebase
Extract common patterns
Generate instructions that encode them
User reviews and approves
```

### Synchronization

Sync instructions between Aidos and external systems:

```
CLAUDE.md in Aidos ↔ Claude.dev website instructions
Cursor IDE .cursor/rules ↔ Aidos instructions
GitHub Copilot instructions ↔ Aidos instructions
```

### Instruction Versioning

Track instruction changes over time:

```
Instruction V1: "Use 2-space indent"
Instruction V2: "Use 4-space indent" (changed 2025-07-15)
Historical queries: "What instructions applied on 2025-06-01?"
```

### Instruction Compliance

Verify that code complies with instructions:

```
Session generates code
System checks compliance with instructions
Flag violations (informally or formally)
```

### Instruction Learning

ML model learns from instructions and project code to improve suggestions:

```
Extract instructions → Represent as embeddings
Match to code sections that follow instructions
Learn patterns
Use for better code generation
```

### Custom DSL for Instructions

Domain-specific language for more expressive instructions:

```
@rule("testing")
@priority(high)
for language in ["python", "rust"] {
  require coverage >= 0.90
  require test_function_naming == "test_*"
}
```

## Open Questions

- Should instructions be versioned alongside project evolution?
- Should instructions have associated explanations (WHY this rule)?
- Should there be a "custom instruction" type for project-specific guidance?
- How should instructions be delivered to AI models (as-is, reformatted, summarized)?
- Should there be a way to "enable/disable" certain instruction sources?
- How should the engine handle contradictory instructions that cannot be automatically resolved?
- Should instructions be queryable (e.g., "what do we say about error handling")?
- Should different sessions see different instructions (e.g., workers see subset of driver instructions)?
