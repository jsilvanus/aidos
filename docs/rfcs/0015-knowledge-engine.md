# RFC-0015: Knowledge Engine

Status: Draft

## Abstract

The Knowledge Engine is a unified system for understanding and querying project knowledge. It synthesizes information from many sources—codebase structure, Git history, build metadata, test results, resources, and embeddings—into a queryable index. Sessions query the Knowledge Engine to understand project context, find relevant code, discover patterns, and make informed decisions. The Knowledge Engine bridges the gap between raw project information (spread across files, tests, documentation) and structured, actionable knowledge (organized for AI reasoning and human understanding).

## Motivation

A project contains vast amounts of information scattered across many forms:

- **Code structure**: Functions, classes, modules, dependencies.
- **Git history**: Commits, branches, merges, and the reasons for changes.
- **Tests**: What the code is supposed to do (test assertions encode intent).
- **Documentation**: Architecture decisions, design patterns, roadmaps.
- **Build metadata**: Dependencies, transitive relationships, build artifacts.
- **Configuration**: Flags, environment variables, system tuning.
- **Package managers**: Versions, compatibility, deprecation notices.

When a session needs to make a decision, it must reason over this information. An AI model cannot directly access the codebase; the session must provide relevant context. But which context is relevant? A 1M-line codebase cannot be fed to the model; neither can arbitrary samples.

The Knowledge Engine solves this by:

- **Aggregating**: Gathering information from multiple sources.
- **Indexing**: Making it searchable and queryable.
- **Ranking**: Returning most relevant results first.
- **Caching**: Avoiding redundant computation.
- **Incrementally updating**: Reflecting changes as the project evolves.

The Knowledge Engine is inspired by:

- **Search engines** (index, query, rank results).
- **Language servers** (understand code structure in real time).
- **Build systems** (track dependencies and transitive relationships).
- **Semantic search** (embeddings for meaning-based discovery).
- **Time-series databases** (Git history as temporal data).

## Goals

1. **Define knowledge sources**: What kinds of information are indexed?

2. **Specify the query model**: How do sessions query knowledge?

3. **Establish provider abstraction**: How are knowledge sources plugged in?

4. **Define caching and incremental updates**: How does the engine stay current?

5. **Clarify relationships**: How does the Knowledge Engine relate to other concepts (resources, artifacts, Intent Graph)?

6. **Explain ranking and relevance**: How are results ordered by relevance?

## Non-goals

This RFC does not specify exact indexing algorithms or data structures. Implementation details are deferred.

This RFC does not mandate a specific embedding model or semantic search approach. Semantics are described; implementation varies.

This RFC does not address distributed knowledge across multiple machines. Single-project knowledge is the design assumption.

This RFC does not specify performance characteristics or scalability limits. Those are implementation concerns.

This RFC does not define machine learning approaches for ranking. Heuristics are acceptable for MVP.

## Design

### Knowledge Sources

The Knowledge Engine aggregates from multiple sources:

#### Codebase Analysis (GitSema, Tree-sitter, LSP)

Structural information about code:

- **Symbols**: Functions, classes, modules, types, constants (with locations).
- **Call graphs**: Which functions call which.
- **Dependencies**: Import relationships between modules.
- **Type information**: Signatures, parameter types, return types.
- **Documentation**: Docstrings, comments attached to symbols.
- **Usage examples**: Where symbols are used in the codebase.

Example query:

```
"Find all functions that implement caching"
→ Search docstrings and comments for "cache"
→ Return matching functions with locations
```

#### Git History

Temporal information about changes:

- **Commits**: What changed, when, by whom, with what message.
- **Blame**: Which commit last modified each line.
- **Branches**: Current branches and their purpose.
- **Merges**: Merge history and resolution decisions.
- **Tags**: Release tags and version information.

Example query:

```
"What changes were made to payment processing last month?"
→ Search commit log for "payment" messages, restricted to last 30 days
→ Return commits with diffs
```

#### Test Suite

Understanding of intended behavior:

- **Test files**: Which test files exist, what they test.
- **Test assertions**: What do tests assert about behavior?
- **Coverage**: Which code is covered by tests, which is not.
- **Test results**: Latest test run results.

Example query:

```
"What does the caching system do?"
→ Find tests related to caching
→ Extract assertions (intended behavior)
→ Summarize for user
```

#### Build Metadata

Dependency and artifact information:

- **Dependencies**: What external packages does the project use?
- **Versions**: Current versions, version constraints.
- **Build artifacts**: What does the build produce?
- **Build times**: Performance baseline.

Example query:

```
"What HTTP libraries are we using?"
→ Query dependency graph
→ Return [reqwest 0.11, http 0.2]
→ Link to docs, changelogs
```

#### Resources (RFC-0013)

Authored knowledge:

- **Architecture documents**: System design, component descriptions.
- **Coding standards**: Conventions, patterns, anti-patterns.
- **Roadmaps**: Planned features, direction.
- **Decision logs**: Rationale for architectural choices.

Example query:

```
"What are our coding conventions?"
→ Query resources tagged "standards"
→ Return Coding Standards resource
```

#### Embeddings

Semantic understanding of code and documentation:

- **Code embeddings**: Vectors representing code semantics.
- **Text embeddings**: Vectors representing documentation and comments.
- **Similarity search**: Find semantically similar code/docs.

Example query:

```
"How do we handle rate limiting?"
→ Embed the query
→ Find semantically similar code
→ Return rate limiting implementations
```

### Provider Abstraction

Knowledge comes from **providers**. The Knowledge Engine defines a provider interface:

```
KnowledgeProvider {
  name: String                      # "GitSema", "TreeSitter", "Embeddings"
  
  /// Return indexed knowledge of given type
  query(query_type: String, params: Map) -> List<KnowledgeItem>
  
  /// Check if provider has current information
  is_current() -> Boolean
  
  /// Update indices if needed
  update() -> void
  
  /// Register for change events
  subscribe(topic: String) -> Subscription
}

KnowledgeItem {
  id: String
  provider: String
  type: String                      # "function", "commit", "test", "resource"
  source_location: Location?        # File, line, etc.
  content: String                   # Docstring, commit message, etc.
  metadata: Map<String, Any>
  relevance_score: Float?           # Optional ranking
}
```

Providers are pluggable. Future providers can be added without modifying the Knowledge Engine:

```
Built-in providers (MVP):
  - GitSema (code structure)
  - GitHistory (Git commits)
  - TestAnalyzer (test suite)
  - ResourceProvider (resources)

Future providers:
  - SemanticEmbeddings
  - BuildDependencies
  - PackageMetadata
  - CustomKnowledgeProvider (plugins)
```

### Queries and Responses

Sessions query the Knowledge Engine:

```
Query {
  type: String                      # "function", "relevant_code", "history", etc.
  keywords: List<String>?           # Search terms
  filters: Map<String, Any>?        # Restrict by type, date, author, etc.
  limit: Int?                       # Max results (default 10)
  include_reasoning: Boolean?       # Include why results are relevant
}

Response {
  results: List<KnowledgeItem>
  total_count: Int
  time_ms: Int                      # Query time
  reasoning: String?                # Explanation of ranking
}
```

Example queries:

```
// Find functions implementing authentication
{
  type: "function",
  keywords: ["authentication", "login"],
  filters: { module: "auth" },
  limit: 5
}

// Find recent changes to payment code
{
  type: "history",
  keywords: ["payment"],
  filters: { since: "2025-07-01" },
  limit: 20
}

// Find code similar to this snippet
{
  type: "semantic",
  content: "fn cache_get(key: &str) -> Option<Vec<u8>> {...}",
  limit: 3
}

// What tests cover this module?
{
  type: "test_coverage",
  filters: { module: "payment" }
}
```

### Index identity: address by content hash, not by path

**The unit of index identity is the Git object hash, not the file path.**

A path is a *name* that points to different content at different times, and to different content
on different branches. Indexing by path therefore requires invalidation on every change and
every checkout, re-embeds identical content once per commit that touches it, and cannot answer
questions about history without a second index.

A blob hash is immutable and content-addressed. Index it once, and the entry is correct
permanently, on every branch, in every commit that has ever referenced it.

This is the model proven by GitSema (`jsilvanus/gitsema`): *"treats blob hashes as the unit of
identity, so identical content is only embedded once regardless of how many commits reference
it."* It is also the model the rest of the architecture already converged on — content-addressed
blobs with reference counting (RFC-0056), object-database-first reads (RFC-0053), and an indexed
`content_hash` on every ContentNode (RFC-0024).

Four consequences, in rising order of importance:

1. **Deduplication is automatic.** A file unchanged across 500 commits is embedded once. A file
   copied between directories is embedded once. Vendored dependencies shared between branches
   are embedded once.

2. **History is nearly free.** Searching across time is not a separate feature. Every blob ever
   committed is already in the index, and "what did this look like before the refactor?" is a
   lookup rather than a re-index.

3. **Incremental indexing is a set difference.** Indexing a new commit means: list the blobs in
   its tree, subtract the blobs already indexed, embed the remainder. Usually a handful of files.

4. **Branch switching costs nothing.** This is the one that matters most, and it removes a
   problem RFC-0053 otherwise has to solve. `git checkout` changes which blobs are *visible*; it
   changes no blob. So there is nothing to invalidate. The reconciliation protocol updates the
   path→blob mapping — a cheap tree read — and every derived fact remains valid.

On a phone, where re-indexing is expensive in both battery and time, that last point is the
difference between semantic search being usable and being something the user turns off.

### Addressing classes

Not all knowledge is blob-addressable, and pretending otherwise is how caches go stale silently.
Every provider declares which class each fact belongs to.

| Class | Key | Cacheable | Examples |
|---|---|---|---|
| **Blob-addressed** | blob SHA | **forever** | embeddings, tree-sitter symbols, text extraction, per-file summaries, OCR output |
| **Tree-addressed** | tree SHA | **forever** | import graph, call graph, project structure at a snapshot |
| **Commit-addressed** | commit SHA | **forever** | diffs, commit messages, blame, authorship |
| **State-addressed** | working-tree fingerprint | **no — invalidate** | uncommitted edits, LSP cross-file types, build metadata, test results, dependency resolution |

The first three are immutable by construction and never require invalidation. Only the
state-addressed class does, and it is the smallest and cheapest to recompute.

Tree-addressed facts are worth noting: a call graph is a function of an entire snapshot, but
tree hashes are immutable too, so it is cacheable — and computable *incrementally* from the
blobs that changed between two trees rather than from scratch.

### Caching and updates

```kotlin
data class IndexEntry(
    val addressKey: String,        // blob, tree, or commit SHA; or a state fingerprint
    val addressClass: AddressClass,
    val providerId: String,
    val providerVersion: Int,      // bump to invalidate this provider's entries
    val facts: ByteArray,
    val computedAt: Instant
)
```

### Index storage

**The index lives outside the operational database**, at `.aidos/index/` (RFC-0054). It is never
in `state.db`. Two reasons: embedding writes would contend with the single writer (RFC-0007),
and vectors would inflate the file the user backs up and exports with data that is entirely
rebuildable.

**Start with brute force and measure before adding a dependency.** For a typical project — a few
thousand unique blobs — an exhaustive cosine scan over a memory-mapped float array is on the
order of milliseconds, well inside the 200 ms query target (RFC-0045), and it has no dependency,
no index build step, and no corruption mode. An approximate-nearest-neighbour index earns its
place only when measurement shows brute force missing the target on a real repository on a real
phone.

This is deliberate sequencing, not laziness: a vector index chosen before the workload is known
is a dependency chosen on a guess, and on mobile the guess costs binary size and a native build.

`providerVersion` is the only invalidation mechanism needed for immutable classes. When a
provider's extraction logic changes, bump the version; old entries become unreachable and are
reclaimed (RFC-0056). No timestamps, no staleness flags, no cache-coherence protocol.

Updates happen:

1. **On commit or checkout**: read the new tree, diff the blob set against the index, enqueue
   the difference. Usually a handful of blobs.
2. **On working-tree change**: only state-addressed facts are affected. Uncommitted file content
   is indexed under a content hash computed on the fly, so an edited-but-uncommitted file is
   still blob-addressed — it simply has a hash Git has not seen yet.
3. **On provider upgrade**: bump `providerVersion`; re-index lazily on query rather than eagerly.

Indexing runs on the `background` dispatcher in cancellable batches (RFC-0007), and on MOBILE it
is deferred work with no latency guarantee (RFC-0049). Queries never block on indexing: they run
against whatever is indexed and mark results from unindexed content as such.

### GitSema

GitSema is the reference implementation of this model and the first knowledge provider.

**On DESKTOP and HEADLESS_SERVER**, it integrates today with no new work: it already exposes an
MCP server, so it is registered at user scope and enabled per project (RFC-0031, RFC-0054).

**On MOBILE it cannot run.** It requires a Node.js runtime, `git` on `PATH`, and a native SQLite
binding — three independent blockers under the mobile profile (RFC-0049). Semantic search on a
phone therefore needs a native KMP provider implementing the same blob-addressed model against
JGit's object database and the local embedding model.

That port is real work, but it is the correct work: the mobile use case is *understanding a
codebase offline*, and understanding without semantic retrieval is grep. The addressing model
above is what makes it feasible on a phone at all.

### Ranking and Relevance

Results are ranked by relevance. Heuristics for MVP:

1. **Keyword match**: How many query terms appear in the result?
2. **Recency**: Recent changes rank higher.
3. **Popularity**: Frequently-used code ranks higher.
4. **Test coverage**: Well-tested code ranks higher.
5. **Location specificity**: Results in the queried module rank higher.

Example ranking:

```
Query: "Find caching code"

Result 1: CacheManager struct in cache.rs
  - All keywords match ✓
  - 95% test coverage
  - 200+ uses in codebase
  - Score: 0.95

Result 2: Comment in utils.rs mentioning "cache"
  - Partial keyword match
  - Not in a module named "cache"
  - Score: 0.42
```

Semantic ranking (future):

```
Embed query: "Find implementations of cache eviction"
Find semantically similar code
Rank by embedding distance
```

### Querying Patterns

Common session queries:

#### Understanding

"What does this module/function do?"

```
Query Knowledge Engine:
  - Function docstrings and comments
  - Tests for the module (encode intended behavior)
  - Related code (what calls this, what it calls)
  - Design decisions (from resources)
```

#### Finding Examples

"How do we implement X? Show me an example."

```
Query Knowledge Engine:
  - Find implementations of X in codebase
  - Find tests for X
  - Find documentation about X
  - Rank by relevance and simplicity
```

#### History and Context

"Why did we implement it this way? What changed?"

```
Query Knowledge Engine:
  - Git blame for relevant code
  - Commit messages explaining changes
  - Decision log entries
  - Related architecture documents
```

#### Dependency Analysis

"What does this code depend on? What depends on it?"

```
Query Knowledge Engine:
  - Imports and dependencies
  - Call graph (what calls this)
  - Reverse dependencies
  - Version information
```

#### Quality and Risk Assessment

"Is this well-tested? Is it critical?"

```
Query Knowledge Engine:
  - Test coverage
  - Number of uses
  - Recent changes (high recent churn = risky)
  - Issues or TODOs in code
```

## Data Model (Conceptual)

```
KnowledgeIndex {
  project_id: UUID
  
  providers: Map<String, ProviderState>
  
  // Raw indices (maintained by providers)
  symbol_index: Map<String, List<Symbol>>
  commit_index: Index<Commit>
  test_index: Map<String, List<Test>>
  resource_index: List<Resource>
  
  // Derived indices
  call_graph: Graph<String, String>        // caller → callee
  dependency_graph: Graph<String, String>  // module → dependency
  usage_index: Map<Symbol, List<Location>> // where is symbol used
  
  metadata: Map<String, Any>
}

ProviderState {
  provider_id: String
  last_update: Timestamp
  is_current: Boolean
  indexed_items: Int
  cache_size_bytes: Int
}
```

## Lifecycle

### Initialization

When a project is created or Knowledge Engine is enabled:

```
Initialize Knowledge Engine
Scan project for knowledge sources
Bootstrap providers (GitSema, GitHistory, etc.)
Build initial indices
```

Initial indexing may take time for large projects.

### Continuous Operation

As the project evolves:

```
On git commit:
  → GitHistory provider updates
  → Dependent indices (call graph, usage) are invalidated
  → Lazy re-index on next query

On file change:
  → GitSema provider updates
  → Symbol index is refreshed

On test run:
  → TestAnalyzer provider updates
  → Coverage index is refreshed

On resource modification:
  → ResourceProvider updates
```

### Invalidation

Invalidation applies to **state-addressed facts only**. Blob-, tree-, and commit-addressed
entries are immutable and are never invalidated — see "Index identity" above.

| Change | Invalidates | Does **not** invalidate |
|---|---|---|
| `git checkout`, branch switch | nothing | embeddings, symbols, call graph, blame |
| New commit | nothing | everything already indexed; new blobs are *added* |
| History rewrite (rebase, amend) | nothing | entries stay valid; some become unreachable and are reclaimed |
| Working-tree edit | that file's state-addressed facts | its blob-addressed entry, once the new content is hashed |
| Build or dependency change | build metadata, dependency graph | code embeddings and symbols |
| Test run | test results, coverage | everything else |
| Provider logic change | that provider's entries, via `providerVersion` | other providers |

The row that matters: **branch switching invalidates nothing.** An earlier version of this RFC
treated a filesystem change as making the index stale, which on a repository with active
branches meant near-continuous re-indexing — and on a phone, that is the difference between a
feature and a battery complaint.

## Examples

### Example 1: Understanding a Module

Session queries: "Help me understand the payment module"

```
Knowledge Engine queries:
  1. Find all files in payment/ module
  2. Extract docstrings and comments
  3. Find tests for payment
  4. Find commits modifying payment
  5. Find resources mentioning payment
  6. Find usages of payment functions

Returns:
  - Module structure (functions, classes, types)
  - Test examples (show intended behavior)
  - Recent changes (git blame, commit messages)
  - Design decisions (from resources)
  - Usage patterns
```

Session now understands payment module context.

### Example 2: Finding Code to Reuse

Session queries: "Find examples of retry logic in the codebase"

```
Knowledge Engine queries:
  1. Find symbols with "retry" in name/docstring
  2. Find tests mentioning "retry"
  3. Find commits mentioning "retry"
  4. Find code semantically similar to retry patterns

Returns (ranked):
  1. ExponentialBackoffRetry struct (50 uses, 95% test coverage)
  2. retry_with_timeout function (20 uses, 80% test coverage)
  3. Commented discussion of retry in git log
  4. Retry pattern in README

Session can copy or adapt top result.
```

### Example 3: Impact Analysis

Session needs to modify a critical function. Query: "What might break if I modify load_user_config()?"

```
Knowledge Engine queries:
  1. Find all calls to load_user_config
  2. Find tests for load_user_config
  3. Find recent changes to load_user_config
  4. Find dependent modules

Returns:
  - 47 call sites (across 12 modules)
  - 23 tests (coverage 92%)
  - Last modified 3 months ago
  - Critical path: initialization, config validation, caching

Session sees high risk and decides to add comprehensive tests first.
```

## Security Considerations

### Information Disclosure

The Knowledge Engine indexes project code and documentation. This index should not leak outside the project.

### Sensitive Content

Secrets (API keys, passwords) that appear in code or comments should be redacted from indices.

### Access Control (Future)

Future work might restrict what knowledge certain sessions can access (e.g., a worker session shouldn't access all company secrets embedded in code).

## MVP Scope

The Knowledge Engine is a **query broker** over pluggable providers, not a monolithic knowledge
base. Its own API surface is narrow and stable (`KnowledgeContextProvider`, RFC-0025); providers
evolve behind it.

The MVP includes:

1. **Content-addressed index storage** with the four addressing classes and `providerVersion`
   invalidation. This is the foundation and must be right before any provider is written —
   retrofitting content addressing onto a path-keyed index is a rebuild.
2. **Blob-addressed providers**: file text extraction, tree-sitter symbols.
3. **Commit-addressed provider**: Git history, messages, blame.
4. **Keyword search** across indexed content, with simple heuristic ranking.
5. **Incremental indexing** as a blob set difference on commit and checkout.
6. **`KnowledgeContextProvider`** returning ranked, token-budgeted `ContextItem`s to prompt
   construction (RFC-0025), each carrying its source node's trust level (RFC-0027).

The MVP does not include:

- **Semantic embeddings.** Deferred to Phase 3, where they are the substance of the offline
  proof (RFC-0099 G3) rather than an enhancement.
- LSP integration (state-addressed, desktop-first, and the most expensive provider to maintain).
- Build metadata and test-result providers.
- Sophisticated ranking, graph traversal queries, cross-project knowledge.

Deliberate sequencing note: content addressing comes first even though embeddings come later,
because the addressing model determines whether embeddings are affordable on a phone at all.

## Future Work

### Semantic Search

Embed code and documentation. Search by semantic similarity:

```
Query: "How do I implement caching?"
→ Embed query
→ Find semantically similar code
→ Return cache implementations (even if not keyword-matched)
```

### Knowledge Graphs

Build explicit knowledge graphs:

```
Concept: "Authentication"
  Related to: users, permissions, security
  Implemented in: auth.rs, login.rs
  Tested by: auth_tests.rs
  Documented in: Security guide resource
```

### Cross-Project Knowledge

Share knowledge across projects (future, when collaboration is added).

### Automatic Summaries

Generate summaries of modules or functions:

```
"Here is a summary of the payment module: [auto-generated summary]"
```

### Anomaly Detection

Detect unusual patterns:

```
"This function has 10x more recent changes than typical"
"This module is unused by any tests"
"This code is more complex than typical for its age"
```

### Temporal Queries

Query how knowledge has changed over time:

```
"How has the caching strategy evolved?"
"Show complexity trends for this module"
"Who were the main contributors to this area?"
```

### Custom Providers

Support plugins for custom knowledge sources:

```
Custom provider: Linter Results
Custom provider: Performance Benchmarks
Custom provider: Security Scans
```

## Open Questions

- How should the Knowledge Engine handle code that is temporarily broken or incomplete (WIP branches)?
- Should embeddings be computed eagerly or lazily? Eagerly is more responsive but costly for large projects.
- How should the Knowledge Engine handle merge conflicts in Git history?
- Should the Knowledge Engine index documentation external to the project (linked wikis, etc.)?
- How should knowledge staleness be communicated to sessions (e.g., "this code may have changed recently")?
- Should the Knowledge Engine rank results differently for different types of queries (e.g., "understand" vs. "find examples")?
- How should performance be balanced against accuracy for large projects?
