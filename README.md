# exist-moderne-recipes

[Moderne](https://www.moderne.io/) / [OpenRewrite](https://docs.openrewrite.org/) recipes for
[eXist-db](https://github.com/eXist-db/exist) — custom code analysis and (eventually)
transformation recipes tailored to this codebase, published to GitHub Packages so they can be
installed into CI (or a local `mod` CLI) without vendoring the source into `exist` itself.

## Recipes

### `org.exist.moderne.XQueryDynamicInvocationCoverage`

Corrects a systematic blind spot in Moderne Prethink's `test-gaps.csv` for this codebase:
`BasicFunction#eval()` overrides (i.e. essentially every builtin XQuery function implementation)
are dispatched by QName through the query engine at runtime, not called directly from Java, so a
static call-graph analysis can never see the edge from a test to `eval()` and always reports it
as untested.

This recipe cross-references each function's QName local name (from `new QName(...)` calls, or
the `FunctionDSL.functionSignature(name, ...)` helper pattern, including through a same-class
`static final String` constant) against string literals in Java test sources and raw text in
test resources (XQSuite `.xql`/`.xqm` modules, XML fixtures), and emits an
`XQueryDynamicInvocationCoverageTable` data table reporting whether each function is actually
exercised by a test.

It's a best-effort heuristic (matches on QName local name only, not the full namespace-qualified
name), so it can be fooled by two different modules sharing a local name. Treat a `false` result
as a real candidate gap; treat a `true` result as reasonably trustworthy given how specific most
eXist-db function names are.

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
`./gradlew publishToMavenLocal -PreleaseVersion=1.0.1-dev3`), or `mod run` will silently return
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
