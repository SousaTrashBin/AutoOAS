# AutoOAS: Generating accurate OpenAPI descriptions from Java source code

[AutoOAS](https://doi.org/10.1016/j.jss.2026.113122) supports the Java frameworks Spring Boot and JAX-RS (`javax.ws.rs.*` and `jakarta.ws.rs.*`).

## Compile and run

AutoOAS uses Java 21 and [Spoon](https://spoon.gforge.inria.fr/) 11, which allows analyzing projects written in Java up to version 21.

It supports Maven projects and Gradle projects that support POM generation via the legacy maven plugin (typically Groovy-based builds). 
Gradle projects using the Kotlin DSL (build.gradle.kts) are not supported at this time.
If the Gradle project under analysis requires an older Java version, please set the `JAVA11_HOME` environment variable to point to a compatible JDK (e.g., Java 11). 
This ensures compatibility with legacy Gradle builds during POM generation. 

Compile and run:
```shell
mvn clean package

java -jar target/auto-oas-1.2.0-jar-with-dependencies.jar <path-to-mvn-project> <oas-output-name>
```

## Evaluation
The script for the evaluation is located in [scripts](./scripts).

### Runtime evaluation
Execute the runtime evaluation script with the following commands. The script also generates the OpenAPI descriptions in the *<output_dir>*.

```shell
mvn clean package -Dmaven.test.skip=true

dataset_dir="path/to/dataset"
output_dir="outputs"
rm -r $output_dir
for _ in {1..5}; do ./scripts/run_runtime_eval.sh $dataset_dir $output_dir -jersey -spring ; done
```

## Docker image
We provide a [Docker image](https://hub.docker.com/r/alexx882/auto-oas) for easier integration into GitHub and GitLab workflows.
```shell
docker build -t alexx882/auto-oas:1.2 .
# or
docker buildx build --push --platform=linux/amd64,linux/arm64 -t alexx882/auto-oas:1.2 .
```

```shell
docker run -v <path-to-mvn-project>:/project alexx882/auto-oas:1.2 /project /project/<oas-output-path-prefix>

# e.g., analyze current directory and write to target/docker-output/
docker run -v `pwd`:/project alexx882/auto-oas:1.2 /project /project/target/docker-output/oas
```

## Academic Use
If you use this project in your academic work, please cite the following paper:

> A. Lercher, D. Jamnig, C. Macho, C. Bauer, and M. Pinzger, “Generating accurate OpenAPI descriptions from Java source code,” Journal of Systems and Software, vol. 244, p. 113122, 2027.

```bibtex
@article{LERCHER2027113122,
  title = {Generating accurate OpenAPI descriptions from Java source code},
  journal = {Journal of Systems and Software},
  volume = {244},
  pages = {113122},
  year = {2027},
  issn = {0164-1212},
  doi = {https://doi.org/10.1016/j.jss.2026.113122},
  url = {https://www.sciencedirect.com/science/article/pii/S0164121226003559},
  author = {Alexander Lercher and David Jamnig and Christian Macho and Clemens Bauer and Martin Pinzger}
  }
```


## MAZE source-only integration

The `feat/maze-source-spring-quarkus` branch adds
`at.aau.serg.parsers.MazeMain`, preserving the upstream parser and schema
transformers. Build the analyzer with Java 21+ and `mvn package`; run its
headless entry point with the analyzer JAR on the classpath:

```sh
java -cp target/auto-oas-1.2.0-jar-with-dependencies.jar \
  at.aau.serg.parsers.MazeMain /path/to/project quarkus /edge prod src/main/java
```

Arguments are the project root, `spring` or `quarkus`, literal deployment context,
one selected profile, and one or more source-relative Java files/directories.
The source-only mode uses Spoon Launcher directly; it does not read or execute
Maven/Gradle build scripts, compile/load target classes, invoke target methods,
resolve network dependencies or write generated files. It emits schema-1 JSON
containing the selected OpenAPI document, exact parsed-file inventory and explicit
coverage. Ordinary parser messages go to stderr. Empty recovery and unsupported
conditions fail instead of yielding a clean document.

Spring MVC uses the existing AutoOAS analysis. Quarkus uses the Jakarta JAX-RS
analysis, excludes registered MicroProfile REST clients from provider endpoints,
and unwraps `Uni<T>` / typed `RestResponse<T>` response bodies by qualified name.
Response status/header behavior of those wrappers is not fully inferred.
`@ApplicationPath` is retained and joined to the explicit deployment context;
configuration files are not interpreted to select context or active profiles.

The selected profile includes unconditional classes, simple positive Spring
`@Profile` names, and literal class-level Quarkus `@IfBuildProfile` /
`@UnlessBuildProfile` values. Profile expressions, method/bean conditions, compound
Quarkus profile conditions and property/custom bean conditions fail as unsupported.
Source dependency types, custom serializers, streaming response shapes and
complete runtime registration remain unknown. This mode currently targets Java
source up to 21 and Jakarta-based Quarkus. It does not establish full API
correctness or independently compare generated and declared specifications.

Lombok is pinned to 1.18.42 and explicitly enabled for analyzer compilation so
Java 23+ compiler defaults cannot silently disable its annotation processor.
Target annotation processors are never run.

The wire-fidelity repair preserves Jackson `JsonProperty.Access` as OpenAPI
`readOnly`/`writeOnly`, honors `JsonIgnore(false)`, and extracts record components
without inheriting Object/Record helper methods as properties. Conflicting
Jackson/OpenAPI access annotations fail rather than produce contradictory flags.
`MazeWireFidelityTest` includes real-source Spring/Quarkus regressions and
independent Jackson serialization/deserialization oracles using the locked
annotations 2.19.0 and databind/core 2.18.2. These synthetic oracle executions are
tests, not part of the source-only analyzer or service-level ground truth.

`mvn -Dtest=MazeMainTest test` exercises real source parsing for both frameworks,
nested DTOs, Quarkus reactive bodies, application paths, profile selection,
REST-client exclusion, empty/malformed inputs and absence of target preparation
side effects. The upstream license, third-party notices and academic citation
above remain applicable.
