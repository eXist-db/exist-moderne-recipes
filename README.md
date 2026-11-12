# exist-moderne-recipes

[Moderne](https://www.moderne.io/) / [OpenRewrite](https://docs.openrewrite.org/) recipes for
[eXist-db](https://github.com/eXist-db/exist) — custom code analysis and (eventually)
transformation recipes tailored to this codebase, published to GitHub Packages so they can be
installed into CI (or a local `mod` CLI) without vendoring the source into `exist` itself.

## Recipes

### `org.exist.moderne.XQueryDynamicInvocationCoverage`

Corrects a systematic blind spot in Moderne Prethink's `test-gaps.csv` for this codebase:
`eval()` overrides of `org.exist.xquery.Function` subclasses (including `BasicFunction`, i.e.
essentially every builtin XQuery function implementation) are dispatched by QName through the
query engine at runtime, not called directly from Java, so a static call-graph analysis can never
see the edge from a test to `eval()` and always reports it as untested.

For each function class (excluding `UserDefinedFunction`), the recipe takes the QName(s)
registered by its `FunctionSignature`: the first argument to `new FunctionSignature(...)` or a
`functionSignature(...)`/`functionSignatures(...)` helper. That argument may be a
`new QName(local, NAMESPACE_URI, PREFIX)`, a `String`/`QName` constant declared anywhere in the
codebase, or a bare local name passed to a module helper such as `UtilModule.functionSignature`,
whose own `new QName(name, NAMESPACE_URI, PREFIX)` supplies the namespace. QNames built for other
purposes (e.g. result elements inside `eval()`) are ignored.

It then scans the code that eXist-db's test runners actually execute (the `@XQSuite` suites run by
`XQSuiteTestEngine`):

| Source | What is scanned |
|---|---|
| XQuery files (`.xq`, `.xql`, `.xqm`, `.xquery`, `.xqy`, `.xqws`) in test source sets — XQSuite modules | in a module with XQSuite annotations, only the code that runs: the prolog (imports, variables), the test functions (`%test:assert*`, `%test:setUp`, ...; `%test:pending` ones never run), functions with other prefixed annotations such as RESTXQ's `%rest:GET`, and the module functions they transitively call. Other XQuery files are scanned whole |
| XML `<TestSet>` files in test source sets (run by `XMLTestRunner`/`test.xq`) | each test's `<code>` and `<xpath>`, plus the set's `<imports>`, `<variable>` and `<functions>` prolog; tests with an `@ignore` other than `"no"` are skipped, as are `<task>`, `<description>` and `<expected>`. Other XML files are data fixtures and are ignored |
| Java test classes | string literals, with `"a" + "b"` concatenations folded |
| The runner libraries `xqsuite/xqsuite.xql` and `test.xq` | the whole file, since it runs with every suite |
| BATS container tests (`.bats`) in test source sets, run by the `ci-container` workflow | the whole file |
| REST API `<query xmlns="http://exist.sourceforge.net/NS/exist">` request bodies in test source sets | the `<text>` |

Test source sets are `src/test*/`, `src/*Test/`, or a top-level `test/`/`tests/` directory.

In each, it finds calls (`prefix:name(`, `name (`), function references (`prefix:name#1`) and
`xs:QName('prefix:name')` lookups, ignoring XQuery `(: comments :)`, annotations and
`declare function` declarations. A prefix resolves to a namespace through the text's own
`declare namespace`/`import module namespace`/`module namespace` declarations or, failing that, the
statically bound prefixes: `fn`, `map`, `array`, `math`, `xs`, and each module's default `PREFIX`.
An unprefixed call is in the default function namespace (`fn:` unless redeclared). A function then
matches on **namespace and local name**, so `fn:contains`, `ngram:contains` and `range:contains`
are told apart; only a function whose namespace could not be resolved falls back to matching on
its local name alone (its `Namespace URI` column is empty).

It also records each call's **arity**: the number of top-level arguments (one more after a `=>`
arrow), a reference's `#n`, or `function-lookup(..., n)`; and the arities each function's
signatures declare: the length of the `SequenceType[]` passed to `new FunctionSignature(...)`
(inline, `FunctionSignature.NO_ARGS` or another array constant, `n+` if `variadic`), or the
parameters of FunctionDSL's `functionSignature(...)`/`functionSignatures(..., arities(arity(...), ...))`.

The resulting `XQueryDynamicInvocationCoverageTable` data table has one row per function name:

| Column | Meaning |
|---|---|
| Source path, Class name | the `Function` subclass |
| QName namespace URI, QName local name | the name it registers; the namespace is empty if unresolved |
| Dynamically invoked in tests | whether any executed test code calls it — the corrected coverage signal for its `eval()` |
| Test kinds | how CI runs the matching tests: `unit` (Surefire, including the XQSuite/XML suites), `integration` (Failsafe `IT*`/`*IT`/`*ITCase` classes, a separate CI job), `container` (BATS against the Docker image), `runner` (the test runner libraries — executed, but not asserted on) |
| Declared arities | e.g. `1, 2`, or `1+` if variadic; `?` for a signature whose arity couldn't be resolved |
| Untested arities | declared arities no matching call uses — partial coverage of an `eval()` that switches on its arguments |
| Matching test paths | where the calls are |

It's a best-effort heuristic: it can't tell whether a call it finds in reachable code actually
runs (a branch not taken, a test that fails before it), and it can't follow calls across XQuery
modules. Treat a `false` result as a real candidate gap and a `true` result as reasonably
trustworthy; a function covered only by `integration`, `container` or `runner` tests deserves a
closer look.

### `org.exist.moderne.TestGapsCoverage`

Patches Prethink's `.moderne/context/test-gaps.csv` with real test coverage. Prethink finds untested
methods from a static call graph, which cannot work for an interpreter: the `eval()` of every builtin
function and expression is dispatched at runtime, so it is always listed as untested. This recipe joins
each row to the matching method of the per-module JaCoCo reports (`<module>/target/site/jacoco/jacoco.xml`,
the files `mvn jacoco:report` writes) by class, method name and erased parameter types, and appends
columns. Every row and every Prethink column, including `Risk score` and `Gap reason`, is left as it is.

| Column | Value |
|---|---|
| `Coverage state` | `covered`, `partial` or `uncovered` (JaCoCo instructions); `no-coverage-data` if neither the row's module nor any other report holds its class, i.e. its tests did not run; `unmatched` if there is a report for the module or the class but no method matched |
| `Instructions covered`, `Instructions missed` | JaCoCo instruction counter of the matched method |
| `Branches covered`, `Branches missed` | JaCoCo branch counter; empty if the method has no branches |
| `Match tier` | `exact`, `arity-unique` (the only method of compatible arity, for types Prethink could not resolve) or `none` |
| `Adjusted risk score` | `Risk score` scaled by the share of the method's instructions no test reaches (rounded to two decimals, so `0` for a fully covered method); equal to `Risk score` where there is no match or no instructions |

Options: `jacocoReportsDir` (required; absolute, or relative to the working directory of the run) and
`testGapsPath` (default `.moderne/context/test-gaps.csv`). If the directory is missing or holds no reports
the CSV is left unchanged and a warning is logged. Running the recipe again replaces the appended columns.

If several reports hold the same class (reports built from merged execution data of all modules), the
match with the best coverage wins, so code that only another module's tests exercise counts as covered.
With one report per module, as `mvn jacoco:report` writes by default, such code shows as uncovered. Coverage
is from the unit-test leg only.

Run it after a second `mod build` that follows Prethink's `mod git apply`: the recipe edits the file in the
LST, and a build from before the apply holds a stale copy whose patch does not fit the file on disk.

## Building

```bash
./gradlew test
./gradlew publishToMavenLocal   # for local `mod` CLI use
```

## Running locally

```bash
mod config recipes jar install org.exist.moderne:exist-moderne-recipes:<version>
mod build /path/to/exist
mod run /path/to/exist --recipe org.exist.moderne.XQueryDynamicInvocationCoverage --no-patch
```

**Important:** `mod run` appears to cache results by recipe GAV rather than jar content — use a
fresh version on every change before reinstalling (e.g.
`./gradlew publishToMavenLocal -PreleaseVersion=2.0.0-dev3`), or `mod run` will silently return
stale results from a previous run. Untagged builds default to a `-SNAPSHOT` version.

Read the resulting data table via
`mod study working-set --data-table XQueryDynamicInvocationCoverageTable --last-recipe-run --csv`,
or decompress `.moderne/run/<runId>/datatables/org.exist.moderne.XQueryDynamicInvocationCoverageTable.csv.gz`
directly.

## Publishing

Tagged releases (`v*`) are published, with the artifact version taken from the tag (`v1.2.3` →
`1.2.3`), to this repository's
[GitHub Packages Maven registry](https://github.com/orgs/eXist-db/packages?repo_name=exist-moderne-recipes)
by [`.github/workflows/publish.yml`](.github/workflows/publish.yml). Consuming the registry
(even for public packages) requires a GitHub token with `read:packages` scope — see that
workflow, or [`exist`](https://github.com/eXist-db/exist)'s own
[`maven-github-settings`](https://github.com/eXist-db/exist/blob/develop/.github/actions/maven-github-settings/action.yml)
action for the credentials wiring pattern used elsewhere in the org.

## License

LGPL 2.1 — see [LICENSE](LICENSE), matching [eXist-db](https://github.com/eXist-db/exist) itself.
