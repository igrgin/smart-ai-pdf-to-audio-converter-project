# Flexible project-structure guidance

Research for the project-wide engineering contract, prompted by [issue #19](https://github.com/igrgin/smart-ai-pdf-to-audio-converter-project/issues/19). Research date: **2026-08-01** (Europe/Zagreb). This note recommends decision rules; it does not itself change the contract or prescribe a folder template.

## Conclusion

A project structure should be a **predictable map of ownership**. A developer who knows the business or user-facing concept should be able to predict where its behavior lives, see the small surface that other parts may use, and change the implementation without searching through unrelated technical buckets.

The durable rule is:

> Group code by the capability, responsibility, or design decision it owns; keep its implementation private; make dependencies explicit and acyclic; introduce another structural level only when it communicates a real distinction in ownership, volatility, or reuse.

This is intentionally not “always use these folders.” Spring Boot explicitly requires no particular layout, React treats some component splits as matters of judgment, and TypeScript project references carry costs as well as benefits. The structure should therefore grow in response to demonstrated complexity, while invariant boundary rules stay enforceable. ([Spring Boot structure](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html), [React component decomposition](https://react.dev/learn/thinking-in-react), [TypeScript project references and caveats](https://www.typescriptlang.org/docs/handbook/project-references))

## General principles

### 1. Start with ownership and likely change, not framework mechanics

Parnas's original modularity paper evaluates decomposition by flexibility, comprehensibility, and independent development, and argues that modules should hide design decisions likely to change rather than merely mirror the processing sequence. That is the strongest general basis for project structure: a structural unit owns a coherent capability and hides the decisions needed to implement it. ([Parnas, “On the Criteria To Be Used in Decomposing Systems into Modules”](https://doi.org/10.1145/361598.361623))

For application code, the first grouping should normally use product or domain language because that is how work is requested and discussed. Technical roles such as HTTP, persistence, rendering, or validation are useful **inside** an owning capability when they make a sufficiently large area easier to scan. They are not a mandatory second level, and they should not scatter one ordinary feature across repository-wide `controllers`, `services`, `repositories`, `components`, `hooks`, and `types` buckets.

Operational test: describe the responsibility without naming a framework. If the description is coherent and distinguishes it from its neighbors, it is a plausible structural boundary. If its description is “miscellaneous code used by several things,” it is not.

### 2. Hide implementation and expose the smallest useful surface

A unit needs a deliberate public contract and private implementation. This follows Parnas's information-hiding criterion and is also embodied in the Java module system: cohesive packages may form a module that explicitly declares dependencies and exports only selected packages. ([Parnas](https://doi.org/10.1145/361598.361623), [Java Language Specification, packages and modules](https://docs.oracle.com/javase/specs/jls/se22/html/jls-7.html))

“Public” means usable across the boundary, not merely declared with a language keyword. Public surfaces include Spring beans and events consumed by another module, React components or hooks imported by another feature, exported TypeScript types, routes, and test fixtures. Everything else remains owned and changeable by the unit.

Do not create an interface, barrel file, or `api` directory solely for symmetry. Create a boundary artifact when an actual consumer needs a stable capability. Conversely, do not let consumers deep-import implementation files merely because the language permits it.

### 3. Keep dependency direction visible and free of cycles

Dependencies between top-level capabilities should form an understandable directed graph. A cycle makes ownership ambiguous: neither side can be understood, changed, or tested without the other. Spring Modulith's structural verifier makes the same rules concrete by rejecting module cycles, access to module internals, and dependencies outside an optional allowlist. ([Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html))

When two units need each other's internals, do not mask the cycle with aliases or a generic shared folder. Reconsider the ownership of the shared rule, introduce a narrow contract in the true owner, combine units that are not independently coherent, or use an explicit integration mechanism. Which remedy is correct depends on the semantics; “no cycles” does not imply a fixed layering scheme.

### 4. Colocate code that changes as one responsibility; split only on evidence

Keep the behavior, tests, types, validation, and adapters owned by one capability close enough that a developer can inspect and change the capability without a repository-wide hunt. Add a subfolder or extract a file when one of these conditions becomes true:

- it has a distinct responsibility or hides a different volatile decision;
- it has an independently meaningful name and contract;
- it is reused by a real second consumer without leaking the first consumer's concepts;
- its size or complexity makes the current file or directory difficult to scan; or
- it needs an independently enforced dependency or build boundary.

React's documentation gives the same incremental signal: a component should ideally concern itself with one thing, can stay together while simple, and can be decomposed when it grows; splitting nested components into files improves scanning and reuse. ([Thinking in React](https://react.dev/learn/thinking-in-react), [importing and exporting components](https://react.dev/learn/importing-and-exporting-components))

This rule avoids both extremes: large flat folders whose files have no visible relationships, and ceremonial directory trees containing one file per category.

### 5. Put each invariant and piece of mutable state under one clear owner

When a new file could fit in two places, place it with the unit that owns the invariant, state transition, or user outcome it implements. Other units call that owner's public contract instead of duplicating the rule.

React makes state ownership explicit: locate the components that use a state value and keep it in their closest sensible common owner; keep the state minimal and avoid redundant or duplicate representations. Custom Hooks share stateful **logic**, not state itself, and should describe concrete high-level use cases. ([Thinking in React](https://react.dev/learn/thinking-in-react), [choosing state structure](https://react.dev/learn/choosing-the-state-structure), [reusing logic with custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks))

The broader inference is that data ownership and source ownership should agree. If multiple areas independently keep the same rule or mutable fact, the folder tree is documenting a false boundary.

### 6. Use names as navigation, not decoration

Names should identify the capability and responsibility in project language. A developer should not need to open a file to learn whether `Manager`, `Helper`, `Common`, or `Utils` means policy, orchestration, mapping, I/O, or formatting. React explicitly recommends meaningful component and file names because anonymous default exports make debugging harder, and Hook naming communicates that the Rules of Hooks apply. ([React imports and exports](https://react.dev/learn/importing-and-exporting-components), [custom Hook naming](https://react.dev/learn/reusing-logic-with-custom-hooks))

Use a generic shared name only when the abstraction itself has a stable, precise meaning across consumers—for example, a deliberately owned design system or protocol client. “Used from two places” is not by itself a coherent responsibility.

### 7. Prefer rules that can be checked over prose that can silently drift

The contract should state semantic rules first, then require the strongest proportionate check available. Automated checks are appropriate for dependency cycles, access to internals, allowed dependency directions, forbidden deep imports, naming conventions, and build boundaries. Human review remains necessary for whether a unit is coherent, names match the project language, or an abstraction was extracted prematurely.

Do not use line counts, exact file counts, or mandatory directory depth as architectural goals. Those numbers can be review signals, but they do not prove ownership, cohesion, or findability.

## Placement decision for a new file

Apply these questions in order:

1. **Who owns the outcome?** Name the business capability, user-facing feature, platform concern, or independently reusable library responsible for the behavior.
2. **What decision does the code hide?** Identify the rule, state, external-system detail, UI responsibility, or transformation likely to change.
3. **Is it part of the owner's contract or implementation?** Expose only what another unit actually needs; keep the rest internal.
4. **Does the owner already have a predictable home for it?** Prefer that home. Add a subgroup only when it communicates a real distinction listed under principle 4.
5. **Would placing it there create a forbidden or cyclic dependency?** If so, revisit ownership or define a narrow interaction; do not bypass the boundary.
6. **Is “shared” justified?** Require a coherent, independently nameable abstraction with multiple real consumers and no feature-specific vocabulary. Otherwise keep it with the first owner and extract later.
7. **Can the decision be explained in one sentence?** If two locations remain equally plausible, the current boundaries or names need clarification before another convention is added.

This is a general algorithm, not a required tree. The same questions work for a Spring controller, domain policy, repository adapter, React component, Hook, API client, schema, or test.

## Spring Boot and Java application

The general rules map to Spring without requiring a universal package layout:

- Put the `@SpringBootApplication` class in a root package above application code and avoid the unnamed/default package so component and entity scanning stay inside the application. Spring Boot documents this behavior and shows business-oriented `customer` and `order` packages as a typical—not required—layout. ([Spring Boot structure](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html))
- Treat each substantial business capability as an application module with a provided interface, internal implementation, and explicit required interfaces. Spring Modulith defines a module in exactly these terms and supports starting simple, then adopting more sophisticated arrangements only as needed. ([Spring Modulith fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html))
- Inside a module, keep the package flat while it is easy to scan. Introduce packages for domain policy, application orchestration, inbound/outbound adapters, persistence, or configuration only when those distinctions are real and useful in that module. Do not require every module to contain every category.
- Keep framework-facing adapters with the capability they adapt. Promote configuration to a cross-application composition area only when it genuinely wires multiple modules or configures the runtime as a whole.
- Use Java package-private visibility where one package is sufficient. When subpackages require public Java types internally, use an architectural verifier because Java `public` alone cannot express “public inside this application module, private to other modules.” Spring Modulith documents this exact limitation and its API/internal package model. ([Spring Modulith fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html))

### Spring verification options

- Run `ApplicationModules.of(Application.class).verify()` in CI when adopting Spring Modulith. It checks cycles, API-only inter-module access, and optional allowed dependencies. ([Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html))
- Use ArchUnit when the chosen boundaries do not match Spring Modulith's conventions or when additional project-specific rules are needed. ArchUnit can check package/class dependencies, containment, layers, slices, and cycles through ordinary tests. ([ArchUnit user guide](https://www.archunit.org/userguide/html/000_Index.html))
- Test each capability through its observable contract. Spring Modulith's module tests can bootstrap one application module and selected dependencies; its documentation also calls an excessive number of mocked external-module beans a sign of high coupling. ([Spring Modulith module testing](https://docs.spring.io/spring-modulith/reference/testing.html))

These are mechanisms, not a mandate to add Spring Modulith, JPMS, or ArchUnit to every codebase. Choose the lightest mechanism that can enforce the boundary the project actually has.

## React and TypeScript application

The same rules apply with React-specific ownership signals:

- Use a user-visible feature or coherent application capability as the coarse ownership boundary. Within it, let the UI hierarchy and data model guide component boundaries; keep small private components in the same file and split when a distinct concern, scanning problem, or real reuse appears. ([Thinking in React](https://react.dev/learn/thinking-in-react), [React imports and exports](https://react.dev/learn/importing-and-exporting-components))
- Keep feature-specific components, Hooks, state transitions, API translation, types, validation, and tests owned by that feature. A route or application shell composes features; it should not become the hidden owner of their rules.
- Locate mutable UI state at its closest coherent owner. Do not duplicate derived data in state. Extract a custom Hook when it expresses a concrete reusable behavior or hides an external-system detail, not merely to move code or imitate lifecycle methods. ([choosing state structure](https://react.dev/learn/choosing-the-state-structure), [reusing logic with custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks))
- Keep Effects at the external synchronization boundary. React describes Effects as an escape hatch for synchronizing with non-React systems and says unnecessary Effects make code harder to follow and more error-prone. Treating an `effects` folder as a general behavior bucket would obscure ownership. ([You Might Not Need an Effect](https://react.dev/learn/you-might-not-need-an-effect))
- Define a small import surface for each feature or library boundary. Keep internal modules deep-importable only from within their owner; do not automatically re-export every internal symbol.
- Introduce a separate TypeScript project only when there is a real logical/build boundary large enough to justify it. Project references can enforce logical separation and improve build/editor performance, but require declaration outputs/build orchestration and have navigation/performance caveats. ([TypeScript project references](https://www.typescriptlang.org/docs/handbook/project-references))

### TypeScript verification options

- Use ESLint import restrictions or a purpose-built boundary rule to reject cross-feature internal imports and forbidden dependency directions. ESLint's core `no-restricted-imports` rule supports exact paths and patterns, including TypeScript type-import handling. ([ESLint `no-restricted-imports`](https://eslint.org/docs/latest/rules/no-restricted-imports))
- Use TypeScript project references only for boundaries that deserve separate compiler projects; `tsc --build` then understands their order and public declaration outputs. ([TypeScript project references](https://www.typescriptlang.org/docs/handbook/project-references))
- If path aliases are used for readable stable entry points, configure the runtime/bundler consistently. TypeScript warns that `paths` changes TypeScript's lookup only and does not rewrite emitted imports. Do not use aliases to disguise deep imports or cycles. ([TypeScript `paths`](https://www.typescriptlang.org/tsconfig/paths.html))
- For separately published or workspace packages, package `exports` can make supported entry points explicit and block ordinary package-name imports of undeclared subpaths. ([Node.js package entry points](https://nodejs.org/api/packages.html#package-entry-points))

## Anti-patterns and the rule they violate

| Anti-pattern | Why it fails | Governing correction |
| --- | --- | --- |
| One universal tree copied into every project or module | Empty or artificial categories do not communicate ownership; frameworks do not require one layout | Start simple and add structure only for a real distinction |
| Repository-wide technical buckets as the only application map | One feature is scattered across unrelated directories and changes cross many boundaries | Make capability/ownership the first navigation axis; use technical subgroups locally when useful |
| A large flat business folder | The top-level capability is visible, but internal responsibilities and public surface are not | Split on distinct responsibility, hidden decision, or scanability evidence |
| `common`, `shared`, `utils`, `helpers`, or `types` as dumping grounds | Ownership and dependency direction disappear; feature vocabulary leaks into global code | Require a precise independent name, contract, and real consumers before promotion |
| Public-by-default modules or barrel files that export everything | Consumers couple to implementation, making local change unsafe | Deliberately allowlist the boundary surface |
| Deep imports into another feature/module | Filesystem location becomes an accidental API | Import through the owner's public entry point and enforce it |
| Circular capability dependencies | Neither unit is independently understandable or changeable | Reassign ownership, narrow the interaction, merge false boundaries, or use an explicit integration mechanism |
| Premature extraction into shared libraries or compiler projects | Adds navigation, configuration, versioning, and build costs before independent reuse exists | Keep code local until a coherent second consumer or build boundary exists |
| Symmetrical over-splitting | Many one-file directories and pass-through abstractions make behavior harder to trace | Let modules differ internally according to their actual complexity |
| Hidden dependency paths | Aliases can make forbidden deep imports look legitimate and can diverge from runtime resolution | Use aliases only for stable entry points and verify resolver parity |
| React behavior organized around lifecycle mechanics | Generic Effect or lifecycle wrappers obscure the user intent and external-system boundary | Name concrete high-level behavior; use Effects only for external synchronization |
| Duplicated state, rules, DTOs, or validation across owners | Multiple sources of truth must be synchronized and disagree over ownership | Put the invariant under one owner and derive or translate at explicit boundaries |

The claims in this table are a synthesis from the cited modularity and framework sources. The specific labels “dumping ground,” “symmetrical over-splitting,” and “hidden dependency paths” are diagnostic inferences, not terms defined by those sources.

## Contract-ready rule set

The following is concise enough to become a generalized engineering-contract rule after discussion:

1. Structure application code first by coherent capability, responsibility, or independently reusable platform concern, using project language.
2. Each structural unit owns its invariants and mutable state, exposes a deliberate minimal contract, and keeps implementation internal.
3. Dependencies between peer units are explicit, point through public contracts, and contain no cycles.
4. Colocate code and tests that implement one responsibility. Add files, subfolders, packages, or projects only for a distinct responsibility, hidden volatile decision, demonstrated reuse, scanability problem, or enforceable build boundary.
5. A generic shared area is permitted only for an independently nameable abstraction with a stable contract and multiple real consumers; feature-specific code remains with its owner.
6. Names must communicate business purpose and technical responsibility. Avoid vague catch-all names.
7. Enforce mechanically checkable boundaries in CI. Review semantic cohesion, ownership, naming, and extraction decisions manually.
8. Evolve structure incrementally. New and materially changed code follows the rules; broader moves occur with tests and a scoped migration, not as an unverified big-bang rearrangement.

## Review and verification checklist

A structural change passes when reviewers can answer yes to the applicable questions:

- Can someone starting from a domain or UI term predict the owning area?
- Does each moved or added file have one explainable owner?
- Is the unit's public surface smaller and clearer than its implementation?
- Can its internals change without consumers deep-importing them?
- Are peer dependencies visible, allowed, and acyclic?
- Are tests owned by the same capability, whether colocated or in a mirrored test tree?
- Did every new structural level solve a named scanability, responsibility, reuse, volatility, or build-boundary problem?
- Did any new `shared` abstraction prove a coherent contract and real consumers?
- Do automated checks fail for violations that tooling can detect?
- Can the affected behavior still be verified before and after a move?

The checklist deliberately does not require exact folder names, depth, file size, or identical internal layouts across modules. Those are local design choices; predictable ownership, encapsulation, dependency direction, and changeability are the invariants.
