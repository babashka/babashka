# ADR 0012: Model Expressions in POM Interpolation

## Status

Accepted.

## Context

A POM refers to its own model in expressions such as `${project.version}`
or `${project.build.directory}`. Maven 3.9.16 resolves `${project.X}` and
`${pom.X}` against any field of the model, with per-field inheritance and
defaults from its super POM. `AbstractStringBasedModelInterpolator` looks an
expression up in this order:

1. `basedir` and `baseUri`
2. the build timestamp
3. `project.` and `pom.` model expressions
4. user properties, the POM's properties, system properties, `env.`
5. unprefixed model expressions such as `${version}`, deprecated

babashka's Maven layer resolves a fixed set: `groupId`, `artifactId`,
`version` and `packaging` with and without a prefix, `project.parent.groupId`,
`project.parent.artifactId`, `project.parent.version`, `parent.groupId`,
`parent.version`, `basedir` and `project.basedir`. Before this decision these
values won over a property with the same name. grenadine resolves the same fixed set.

A local repository of 11,592 POMs uses these expressions most:

| expression | uses | supported |
|---|---|---|
| `${project.version}` | 13,115 | yes |
| `${project.build.directory}` | 3,471 | no |
| `${project.groupId}` | 3,088 | yes |
| `${project.artifactId}` | 1,757 | yes |
| `${project.basedir}` | 1,233 | yes |
| `${project.build.outputDirectory}` | 1,073 | no |
| `${project.name}` | 931 | no |
| `${project.organization.name}` | 349 | no |
| `${project.url}` | 266 | no |

Resolution reads dependencies, repositories, parents and relocations. None of
those elements in the same POMs uses an unsupported model expression.
Expressions such as `${project.guava.version}` in those elements name
properties, which already resolve. The one model expression in a repository
URL, `${project.build.directory}`, is in `distributionManagement`, which
resolution does not read.

Three scopes were considered, with their estimated size in `pom.clj`:

- Maven's lookup order only, about 15 lines.
- The common fields above with their inheritance and super POM defaults,
  about 60 lines.
- Every model field, Maven's inheritance rules, super POM and path
  translation, about 400 lines.

## Decision

The fixed set of model expressions stays. Lookups follow Maven's order:
`basedir`, `project.` and `pom.` expressions, the POM's properties, system
properties and the environment, then unprefixed expressions.

## Consequences

- A property named like an unprefixed expression, such as `<version>`,
  wins over the model value, as in Maven.
- A POM that puts an unsupported model expression in a coordinate or a
  repository URL fails resolution with a download error that names the
  literal `${...}`. Add that field, with Maven's inheritance and default for
  it, when such a report comes in.
