# Engineering Contract

This document contains binding project instructions for implementation. Resolve conflicts with the user before writing code.

## Source structure

### Ownership-led organization

**Applies to:** Spring Boot and React/TypeScript production and test source code.

**Contract:**

- Structure source code as a predictable map of ownership. Organize application code first by a coherent business capability, user-facing feature, or independently reusable platform concern, using the project's domain and product language.
- Place behavior, invariants, mutable state, validation, types, adapters, and tests with the area that owns their outcome. Keep code together when it changes as one responsibility.
- Give each file one explainable owner. When ownership is unclear or two locations appear equally valid, clarify the responsibility or the owning areas before placing the code.
- Each owning area exposes only the smallest deliberate interface required by actual callers and keeps its implementation internal. Callers depend on that interface and do not import another area's internal implementation.
- Dependencies between peer areas must be explicit and acyclic. Do not conceal unclear ownership or circular dependencies through generic shared code, path aliases, or deep imports.
- Begin with the smallest structure that remains easy to scan. Add a file, package, folder, or separate project only when it identifies a distinct responsibility, hides a separately changing decision, supports demonstrated reuse, reduces a real navigation problem, or establishes a required build boundary.
- Do not require identical internal structures across owning areas. Do not create empty or one-file categories merely to satisfy a standard tree, and do not retain a large flat area after its internal responsibilities have become difficult to distinguish.
- Create shared code only for an independently nameable responsibility with a stable interface and multiple actual consumers. Code that uses one area's vocabulary or implements one area's rules remains with that owner. Do not use `common`, `shared`, `utils`, `helpers`, or similar names as catch-all destinations.
- Keep application-wide composition, runtime bootstrap, generated sources, and repository tooling in clearly named homes appropriate to those responsibilities rather than assigning them artificially to a business feature.
- Test source follows the same ownership map as the behavior it verifies. Tests may be colocated or placed in a separate mirrored test source tree according to the project's toolchain, without requiring every implementation folder to be reproduced.

For Spring Boot:

- Use the application's business modules as the first package level beneath the application root.
- Keep types deliberately exposed to peer modules at the module's package root. Place the module's implementation beneath `internal`, subdividing it only where the general rules above justify another package.
- Peer modules import only the owning module's exposed types. Framework-facing code such as controllers, persistence adapters, messaging adapters, configuration, and scheduled or asynchronous runners remains with the module whose behavior it supports.
- Keep the Spring Boot application class above the application packages so component scanning covers the application without using the default package.

For React and TypeScript:

- Use user-facing features and coherent application capabilities as the primary source areas. Keep feature-specific components, hooks, state, validation, API translation, types, and tests with their owning feature.
- Give each feature used by another area a deliberate entry point that exports only its supported interface. Other features import through that entry point rather than importing internal files.
- Keep the application shell responsible for application composition, navigation, and application-wide concerns; do not let it become the hidden owner of feature behavior.
- Extract reusable UI or TypeScript code only when it has a precise responsibility and actual consumers independent of a single feature.

## GitHub workflow

### Issue branch starting point

**Applies to:** Any GitHub issue when its implementation branch is created.

**Contract:** Acquire the latest `main` before creating the branch. Every issue that blocks the current issue must be resolved, and the blocking issue's work must be present on `main`, before the current issue's branch is created. Create the issue branch from that `main` state.

**Verify:** At branch creation, the branch starts at the latest `main` commit and every blocking issue is resolved with its work present on `main`.

### Issue branch naming

**Applies to:** Every issue implementation branch.

**Contract:** Name the branch by joining the issue number and issue title, then expressing the full name in kebab case. Use no additional branch-name prefix.

**Verify:** The branch name starts with the issue number, followed by the issue title as lowercase hyphen-separated words, with punctuation omitted.

### Issue branch lifecycle

**Applies to:** Any GitHub issue when implementation begins.

**Contract:** Claim the issue, then create a dedicated branch for that issue. When implementation is finished, push all work on the branch to GitHub and open a pull request.

**Verify:** The issue is claimed before its branch is created. At completion, the branch and all of its implementation work exist on GitHub and a pull request has been opened.

### Pull request target

**Applies to:** Every issue implementation pull request.

**Contract:** Open the pull request from the issue branch into `main`.

**Verify:** The pull request's head is the issue branch and its base is `main`.

### Pull request issue linkage

**Applies to:** Every issue implementation pull request.

**Contract:** Include a GitHub closing link to the branch's issue in the pull request so that merging the pull request into `main` closes the issue automatically.

**Verify:** GitHub shows the pull request as closing its issue, and merging the pull request closes that issue.

### Pull request title

**Applies to:** Every issue implementation pull request.

**Contract:** Use the issue title as the pull request title.

**Verify:** The pull request title exactly matches the title of the issue it closes.

### GitHub operations

**Applies to:** Every GitHub-specific action, including pull request operations.

**Contract:** Perform the action with the GitHub CLI.

**Verify:** GitHub-specific actions are executed through `gh`.

## Spring Boot code

### Persistence

**Applies to:** Spring Boot persistence code.

**Contract:** Use Spring JDBC when correctness or performance depends on explicit SQL, including database-specific operations, ledgers, outbox/inbox and idempotency records, leases, locking or compare-and-set flows, bulk operations, and set-oriented projections. JPA with Hibernate may be used for a module-owned aggregate when its primary use cases manage the lifecycle of a stable, bounded object graph and the ORM materially reduces mapping and update work. Each aggregate has one normal persistence mechanism; do not routinely write the same aggregate through both JDBC and JPA.

**Verify:** Every persistence area satisfies the JDBC or JPA criteria above, and no aggregate has competing JDBC and JPA writers.

### Lombok

**Applies to:** Spring Boot production and test code.

**Contract:** Use Lombok narrowly as a compile-time boilerplate tool:

- Use `@RequiredArgsConstructor` for Spring beans whose dependencies are `private final` fields.
- Use `@Slf4j` when a class needs a logger; generated logging does not relax the project's content-free telemetry rules.
- Use `@Getter` only when the generated accessors are intentionally part of the class API. Use `@Setter` only where mutation is intentional, with the narrowest required access.
- Use `@NoArgsConstructor(access = PROTECTED)` only when a framework contract requires it. Do not use `force = true`.
- Use `@Builder` only on a deliberately designed constructor or static factory when named or optional construction inputs justify it. Do not apply it broadly to classes or persistence entities.
- Use `@NonNull` only when an internal fail-fast `NullPointerException` is the intended behavior. Do not use it as request validation or as the project's nullness model.
- Do not use `@EqualsAndHashCode`, `@Data`, `@Value`, `@SneakyThrows`, `@Cleanup`, `@Synchronized`, or experimental Lombok features.
- Do not use an all-fields generated `toString`. If Lombok generates `toString`, explicitly allowlist only fields that are safe under the content-free telemetry rules.
- Write and test equality explicitly where a persistence or value type requires it.

**Verify:** Keep a root `lombok.config` that stops configuration bubbling and makes prohibited Lombok annotations fail compilation. Compilation, static analysis, and tests pass with Lombok configured as an annotation processor.

### Service interfaces and implementations

**Applies to:** Every service in the Spring Boot codebase.

**Contract:** Define the service's complete callable contract in an interface. Name the implementation by appending `Impl` to the interface name. Wire service beans into production code through the interface type rather than the implementation type.

**Verify:** Every service has an interface, its implementation has the same name followed by `Impl`, and bean consumers depend on the interface.

### Services in tests

**Applies to:** Tests that use or replace a Spring Boot service.

**Contract:** Refer to the service through its interface. Mock the interface rather than the implementation class when replacing a service with a mock.

**Verify:** Service collaborators and service mocks in tests use interface types rather than `Impl` types.

## Configuration and test environments

### Spring configuration hierarchy

**Applies to:** Spring Boot runtime and test configuration.

**Contract:** Use these lowercase Spring profile identifiers and YAML files:

- Main application resources: `application.yaml` for shared defaults, `application-prod.yaml` for the `prod` profile, and `application-dev.yaml` for the `dev` profile.
- Test resources: a test-specific `application.yaml` for shared test defaults, `application-test.yaml` for the `test` profile, and `application-itest.yaml` for the `itest` profile.

Profile files contain only their applicable overrides of the shared defaults.

Production-specific values are supplied through environment variables and resolved by Spring Boot at runtime through placeholders in the production profile YAML. Do not commit deployed production values into the file.

The `test` and `itest` profiles are test-only. Tests may inherit applicable defaults from the base application YAML and override them in test configuration.

**Verify:** The base and profile YAML files load with the intended precedence; production configuration resolves its deployed values from the runtime environment; non-test application runs do not activate a test profile.

### Test profile roles and class names

**Applies to:** Spring Boot tests.

**Contract:** Use the `test` profile for unit tests, mock-based tests, and other ordinary tests that load application configuration. Name every ordinary test class with the suffix `Test`.

Use the `itest` profile for implementation tests. In this repository, an implementation test exercises a specific part of the implementation with the required real supporting services supplied through Testcontainers, including a database when the behavior depends on one; it serves the end-to-end/integration-test role. Name every implementation test class with the suffix `ITest`.

**Verify:** Ordinary test classes match `*Test` but not `*ITest`, and they do not start unnecessary containers. Testcontainers-backed implementation test classes match `*ITest`, activate `itest`, and start and exercise every supporting service required by the behavior under test.

### Test property configuration

**Applies to:** Spring Boot tests that require properties specific to their activated profile.

**Contract:** Never use `@DynamicPropertySource`. Define every required profile-specific property in the configuration YAML for the profile the test activates.

**Verify:** No test uses `@DynamicPropertySource`, and each profile-specific test property is declared in the matching test-resource `application-<profile>.yaml` file.

**Migration:** Remove the existing `@DynamicPropertySource` usage from `PlatformStatusITest`; tracked in [GitHub issue #47](https://github.com/igrgin/smart-ai-pdf-to-audio-converter-project/issues/47).

### Test coverage by behavioral scope

**Applies to:** Every new or changed behavior.

**Contract:** Add or update ordinary `*Test` coverage for the behavior's focused unit, API operation, or other local contract. Add or update an `*ITest` when the behavior is a feature or workflow whose outcome must be proven across application-component or infrastructure boundaries. Exercise implementation tests through the observable feature boundary and verify the complete outcome rather than internal calls.

The number of API calls does not determine the test category. The deciding factor is whether the test isolates a focused behavior or proves a complete feature flow with its required real supporting services.

**Verify:** Every changed behavior has focused test coverage. Changed cross-boundary feature flows also have an `*ITest` that runs with the `itest` profile and the required Testcontainers services.

### Behavior and failure-mode inventory

**Applies to:** Tests for every new or changed behavior.

**Contract:** Before writing test code, enumerate the behavior and its failure modes. Use that inventory to cover the happy path, boundary conditions, invalid inputs, dependency failures, and regression cases.

**Verify:** Each enumerated behavior and failure mode is traceable to test coverage, and every applicable coverage category is represented.
