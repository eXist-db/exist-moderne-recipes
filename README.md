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
| XQuery files (`.xq`, `.xql`, `.xqm`, `.xquery`, `.xqy`, `.xqws`) in test source sets — XQSuite modules | the whole module, minus `%test:pending` functions, which XQSuite never runs |
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

The resulting `XQueryDynamicInvocationCoverageTable` data table reports whether each function is
actually exercised by a test. It's a best-effort heuristic: it does not check arity, and it can't
tell whether a call it finds is reached at run time. Treat a `false` result as a real candidate gap
and a `true` result as reasonably trustworthy.

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
