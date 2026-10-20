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

For each function class (excluding `UserDefinedFunction`), the recipe takes the local name(s)
registered by its `FunctionSignature` — the first argument to `new FunctionSignature(...)` or a
`functionSignature(...)`/`functionSignatures(...)` helper, given inline, as `new QName("...", ...)`,
or through a `String`/`QName` constant declared anywhere in the codebase. QNames built for other
purposes (e.g. result elements inside `eval()`) are ignored.

It then searches every file in a test source set (`src/test*/`, `src/*Test/`, or a top-level
`test/`/`tests/` directory) — string literals in Java tests (with `"a" + "b"` concatenations
folded), and the full text of any other test resource: XQSuite modules, XML fixtures, and so on —
for:

- calls: `prefix:name(` or `name (` (the name must start at a name boundary, so `fn:local-name(`
  does not count as a call to `name`),
- function references: `prefix:name#1`,
- lookups: `xs:QName('prefix:name')`,

ignoring XQuery `(: comments :)` and `declare function` declarations. The resulting
`XQueryDynamicInvocationCoverageTable` data table reports whether each function is actually
exercised by a test.

It's a best-effort heuristic (matches on QName local name only, not the full namespace-qualified
name), so it can be fooled by two different modules sharing a local name. Treat a `false` result
as a real candidate gap; treat a `true` result as reasonably trustworthy given how specific most
eXist-db function names are.

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
