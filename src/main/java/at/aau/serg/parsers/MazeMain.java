package at.aau.serg.parsers;

import at.aau.serg.frameworks.RestFramework;
import at.aau.serg.frameworks.jaxrs.JakartaRestFramework;
import at.aau.serg.frameworks.spring.SpringRestFramework;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.servers.Server;
import spoon.Launcher;
import spoon.OutputType;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Source-only integration entry point: reuses AutoOAS without MavenLauncher,
 * Gradle, target classpaths, compilation, application startup or network calls.
 */
public final class MazeMain {
    private record OperationWitness(String path, String method, String file, int line,
                                    String declaring_type, String method_signature) {
    }
    private static final String SPRING_PROFILE = "org.springframework.context.annotation.Profile";
    private static final String IF_PROFILE = "io.quarkus.arc.profile.IfBuildProfile";
    private static final String UNLESS_PROFILE = "io.quarkus.arc.profile.UnlessBuildProfile";
    private static final String REST_CLIENT = "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: MazeMain <root> <spring|quarkus> <context-path> <profile> <source-root>...");
        }
        PrintStream output = System.out;
        ObjectNode result;
        try {
            System.setOut(System.err);
            result = generate(Path.of(args[0]), args[1], args[2], args[3],
                    Arrays.stream(args).skip(4).map(Path::of).toList());
        } finally {
            System.setOut(output);
        }
        output.println(mapper().writeValueAsString(result));
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public static ObjectNode generate(Path directory, String framework, String contextPath,
                                      String profile, List<Path> sourceRoots) throws Exception {
        Path root = directory.toRealPath();
        if (!Set.of("spring", "quarkus").contains(framework)
                || !profile.matches("[A-Za-z0-9_.-]+")
                || !contextPath.matches("/[A-Za-z0-9._~!&'()*+,;=:@%/-]*")
                || contextPath.startsWith("//") || sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("Invalid framework, profile, context path or sources");
        }
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setOutputType(OutputType.NO_OUTPUT);
        // Only analyzer dependencies can be loaded. Target source is parsed as AST.
        launcher.getEnvironment().setSourceClasspath(new String[0]);
        launcher.getEnvironment().setInputClassLoader(MazeMain.class.getClassLoader());
        Set<Path> files = new TreeSet<>();
        for (Path relative : sourceRoots) {
            Path source = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !source.startsWith(root)
                    || !source.toRealPath().startsWith(root) || Files.isSymbolicLink(source)) {
                throw new IllegalArgumentException("Source root escapes the selected project");
            }
            if (Files.isDirectory(source)) {
                try (var paths = Files.walk(source)) {
                    for (Path file : paths.toList()) {
                        if (Files.isSymbolicLink(file)) {
                            throw new IllegalArgumentException("Symbolic source input is unsupported");
                        }
                        if (Files.isRegularFile(file) && file.toString().endsWith(".java")) files.add(file);
                    }
                }
            } else if (Files.isRegularFile(source) && source.toString().endsWith(".java")) {
                files.add(source);
            } else {
                throw new IllegalArgumentException("Sources must be Java files or directories");
            }
        }
        if (files.isEmpty()) throw new IllegalArgumentException("No Java source selected");
        for (Path file : files) launcher.addInputResource(file.toString());
        CtModel model = launcher.buildModel();
        if (launcher.getEnvironment().getErrorCount() != 0) {
            throw new IllegalArgumentException("Spoon reported incomplete model construction");
        }
        // Reject conditions whose semantics this bounded entry point cannot evaluate.
        for (CtAnnotation<?> annotation : model.getElements(new TypeFilter<>(CtAnnotation.class))) {
            String name = annotation.getAnnotationType().getQualifiedName();
            if (name.equals(SPRING_PROFILE) || name.equals(IF_PROFILE) || name.equals(UNLESS_PROFILE)) {
                if (!(annotation.getParent() instanceof CtType<?>)) {
                    throw new IllegalArgumentException("Method/bean profile selection is unsupported");
                }
                profileValues(annotation);
            } else if (name.startsWith("io.quarkus.arc.properties.")
                    || name.startsWith("org.springframework.boot.autoconfigure.condition.")
                    || name.equals("org.springframework.context.annotation.Conditional")) {
                throw new IllegalArgumentException("Property/custom bean conditions are unsupported");
            }
        }
        RestFramework restFramework = framework.equals("spring") ? new SpringRestFramework() {
            @Override
            public Map<String, List<CtType<?>>> splitClassesOnProfiles(List<CtType<?>> controllers) {
                return Map.of(profile, controllers);
            }
        } : new JakartaRestFramework() {
            @Override
            public Map<String, List<CtType<?>>> splitClassesOnProfiles(List<CtType<?>> controllers) {
                return Map.of(profile, controllers);
            }
            @Override
            public Set<String> getAsyncResultWrapperNames() {
                return Set.of("java.util.concurrent.CompletionStage", "io.smallrye.mutiny.Uni");
            }
            @Override
            public Set<String> getResponseWrapperNames() {
                return Set.of("jakarta.ws.rs.core.Response", "org.jboss.resteasy.reactive.RestResponse");
            }
        };
        Map<String, OpenAPI> generated = new HashMap<>();
        Set<OperationWitness> operations = new HashSet<>();
        RestApiParser parser = new RestApiParser(root.toString(), root.toString(), "",
                restFramework, model) {
            @Override
            protected void operationCreated(CtMethod<?> method, String path, PathItem.HttpMethod httpMethod) {
                var position = method.getPosition();
                if (!position.isValidPosition() || position.getLine() <= 0
                        || position.getFile() == null || method.getDeclaringType() == null) {
                    throw new IllegalArgumentException("Operation has no qualified source witness");
                }
                Path file = position.getFile().toPath().toAbsolutePath().normalize();
                if (!file.startsWith(root) || !files.contains(file) || !Files.isRegularFile(file)
                        || Files.isSymbolicLink(file)) {
                    throw new IllegalArgumentException("Operation source is outside selected owned inputs");
                }
                operations.add(new OperationWitness(path, httpMethod.name(),
                        root.relativize(file).toString().replace('\\', '/'), position.getLine(),
                        method.getDeclaringType().getQualifiedName(), method.getSignature()));
            }
            @Override
            protected RelevantClasses getRelevantClassesFromPackages(Collection<CtPackage> packages) {
                RelevantClasses classes = super.getRelevantClassesFromPackages(packages);
                return new RelevantClasses(
                        selected(classes.getControllerClasses(), profile),
                        selected(classes.getGlobalExceptionHandlerClasses(), profile),
                        selected(classes.getApplicationPathClasses(), profile),
                        selected(classes.getExplicitModelClasses(), profile));
            }
            @Override
            protected void writeOpenApiToFile(OpenAPI openApi, String selectedProfile) {
                generated.put(selectedProfile, openApi);
            }
        };
        parser.run();
        OpenAPI document = generated.get(profile);
        if (document == null || document.getPaths() == null || document.getPaths().isEmpty()) {
            throw new IllegalArgumentException("AutoOAS recovered no operations for the selected profile");
        }
        List<Server> servers = document.getServers();
        if (servers == null || servers.isEmpty()) servers = List.of(new Server().url("/"));
        List<Server> composed = new ArrayList<>();
        for (Server server : servers) {
            String path = server.getUrl();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                throw new IllegalArgumentException("Nonliteral application path is unsupported");
            }
            String prefix = contextPath.replaceAll("/+$", "");
            String suffix = path.replaceAll("/+$", "");
            String combined = prefix + suffix;
            composed.add(new Server().url(combined.isEmpty() ? "/" : combined));
        }
        document.setServers(composed);
        document.getInfo().setTitle("Source provider");
        document.getInfo().setVersion("generated");
        ObjectNode result = mapper().createObjectNode();
        result.put("schema_version", 2).put("engine", "autooas").put("framework", framework)
                .put("profile", profile).put("context_path", contextPath);
        result.set("document", mapper().valueToTree(document));
        result.set("operations", mapper().valueToTree(operations.stream()
                .filter(operation -> document.getPaths().containsKey(operation.path())
                        && document.getPaths().get(operation.path()).readOperationsMap()
                        .containsKey(PathItem.HttpMethod.valueOf(operation.method())))
                .sorted(Comparator.comparing(OperationWitness::path)
                        .thenComparing(OperationWitness::method)
                        .thenComparing(OperationWitness::file)
                        .thenComparingInt(OperationWitness::line)
                        .thenComparing(OperationWitness::declaring_type)
                        .thenComparing(OperationWitness::method_signature)).toList()));
        result.set("files", mapper().valueToTree(files.stream()
                .map(file -> root.relativize(file).toString().replace('\\', '/')).toList()));
        result.set("coverage", mapper().valueToTree(List.of(
                "AutoOAS source-only/no-classpath extraction is heuristic. Dependency types, runtime "
                + "registration, custom serializers, configuration and complete behavioral coverage "
                + "are not established. Source roots, profile and deployment context are explicit; "
                + "declared/generated agreement is not checked.")));
        return result;
    }

    private static List<CtType<?>> selected(List<CtType<?>> types, String profile) {
        return types.stream().filter(type -> {
            for (CtAnnotation<?> annotation : type.getAnnotations()) {
                String name = annotation.getAnnotationType().getQualifiedName();
                if (name.equals(REST_CLIENT)) return false;
                if ((name.equals(SPRING_PROFILE) || name.equals(IF_PROFILE))
                        && !profileValues(annotation).contains(profile)) return false;
                if (name.equals(UNLESS_PROFILE) && profileValues(annotation).contains(profile)) return false;
            }
            return true;
        }).toList();
    }

    private static Set<String> profileValues(CtAnnotation<?> annotation) {
        if (annotation.getValues().keySet().stream().anyMatch(key -> !key.equals("value"))) {
            throw new IllegalArgumentException("Compound build profile conditions are unsupported");
        }
        var expression = annotation.getValue("value");
        var expressions = expression instanceof CtNewArray<?> array ? array.getElements()
                : expression == null ? List.of() : List.of(expression);
        Set<String> profiles = new HashSet<>();
        for (var value : expressions) {
            if (!(value instanceof CtLiteral<?> literal)
                    || !(literal.getValue() instanceof String name)
                    || !name.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("Profile expressions require literal positive names");
            }
            profiles.add(name);
        }
        if (profiles.isEmpty()) throw new IllegalArgumentException("Profile selection is unspecified");
        return profiles;
    }
}
