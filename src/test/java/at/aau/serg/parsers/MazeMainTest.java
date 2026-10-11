package at.aau.serg.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MazeMainTest {
    @TempDir Path root;

    private Path sources() throws Exception {
        Path source = root.resolve("src/main/java/example");
        Files.createDirectories(source);
        Files.writeString(root.resolve("pom.xml"), "<project>not a Maven model</project>");
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh\ntouch application-started\nexit 71\n");
        root.resolve("gradlew").toFile().setExecutable(true);
        Files.writeString(source.resolve("Payload.java"), """
                package example;
                import java.util.List;
                public class Payload {
                  static { System.setProperty("maze.target.executed", "true"); }
                  public String name;
                  public List<Item> items;
                }
                """);
        Files.writeString(source.resolve("Item.java"), """
                package example;
                public class Item { public int count; }
                """);
        return source;
    }

    private JsonNode generate(String framework, String profile) throws Exception {
        return MazeMain.generate(root, framework, "/edge", profile, List.of(Path.of("src/main/java")));
    }

    @Test void springNestedBodiesWithoutTargetPreparation() throws Exception {
        Path source = sources();
        Files.writeString(source.resolve("Resource.java"), """
                package example;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/items")
                public class Resource {
                  @PostMapping(consumes="application/json", produces="application/json")
                  public Payload create(@RequestBody Payload input) { return input; }
                }
                """);
        JsonNode result = generate("spring", "default");
        assertTrue(result.path("document").path("paths").path("/items").has("post"));
        JsonNode models = result.path("document").path("components").path("schemas");
        assertTrue(models.path("Payload").path("properties").has("items"));
        assertTrue(models.path("Item").path("properties").has("count"));
        assertEquals("/edge", result.path("document").path("servers").get(0).path("url").asText());
        assertNull(System.getProperty("maze.target.executed"));
        assertFalse(Files.exists(root.resolve("application-started")));
        assertFalse(Files.exists(root.resolve("spoon.classpath-app.tmp")));
        assertTrue(result.path("coverage").size() > 0);
    }

    @Test void quarkusReactiveBodiesAndApplicationPathWithoutClientEndpoints() throws Exception {
        Path source = sources();
        Files.writeString(source.resolve("Resource.java"), """
                package example;
                import jakarta.ws.rs.*;
                import io.smallrye.mutiny.Uni;
                import org.jboss.resteasy.reactive.RestResponse;
                @Path("/items") @Produces("application/json")
                public class Resource {
                  @GET public Uni<RestResponse<Payload>> read() { return null; }
                }
                """);
        Files.writeString(source.resolve("RestApplication.java"), """
                package example;
                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.core.Application;
                @ApplicationPath("/v1")
                public class RestApplication extends Application {}
                """);
        Files.writeString(source.resolve("Peer.java"), """
                package example;
                import jakarta.ws.rs.*;
                import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
                @RegisterRestClient @Path("/client-only")
                public interface Peer { @GET Payload read(); }
                """);
        JsonNode result = generate("quarkus", "default");
        JsonNode paths = result.path("document").path("paths");
        assertTrue(paths.path("/items").has("get"));
        assertFalse(paths.has("/client-only"));
        assertEquals("#/components/schemas/Payload", paths.path("/items").path("get")
                .path("responses").path("200").path("content").path("application/json")
                .path("schema").path("$ref").asText());
        assertEquals("/edge/v1", result.path("document").path("servers").get(0).path("url").asText());
        assertNull(System.getProperty("maze.target.executed"));
    }

    @Test void quarkusRootApplicationPathPreservesDeploymentPrefix() throws Exception {
        Path source = sources();
        Files.writeString(source.resolve("Resource.java"), """
                package example;
                @jakarta.ws.rs.Path("/items")
                public class Resource {
                  @jakarta.ws.rs.GET public String read() { return "ok"; }
                }
                """);
        Files.writeString(source.resolve("RestApplication.java"), """
                package example;
                @jakarta.ws.rs.ApplicationPath("/")
                public class RestApplication extends jakarta.ws.rs.core.Application {}
                """);
        JsonNode document = generate("quarkus", "prod").path("document");
        assertTrue(document.path("paths").path("/items").has("get"));
        assertEquals("/edge", document.path("servers").get(0).path("url").asText());
        JsonNode rootDocument = MazeMain.generate(root, "quarkus", "/", "prod",
                List.of(Path.of("src/main/java"))).path("document");
        assertEquals("/", rootDocument.path("servers").get(0).path("url").asText());
    }

    @Test void explicitProfilesAndUnsupportedConditions() throws Exception {
        Path source = sources();
        for (String framework : List.of("spring", "quarkus")) {
            String annotation = framework.equals("spring")
                    ? "org.springframework.context.annotation.Profile"
                    : "io.quarkus.arc.profile.IfBuildProfile";
            String controller = framework.equals("spring")
                    ? "@org.springframework.web.bind.annotation.RestController"
                      + " @org.springframework.web.bind.annotation.RequestMapping"
                    : "@jakarta.ws.rs.Path";
            String method = framework.equals("spring")
                    ? "@org.springframework.web.bind.annotation.GetMapping" : "@jakarta.ws.rs.GET";
            for (String profile : List.of("prod", "dev")) {
                Files.writeString(source.resolve(profile + ".java"),
                        "package example;\n@" + annotation + "(\"" + profile + "\")\n"
                        + controller + "(\"/" + profile + "\")\n"
                        + "class " + profile + " { " + method + " public String get() { return \"ok\"; } }");
            }
            JsonNode paths = generate(framework, "prod").path("document").path("paths");
            assertTrue(paths.has("/prod"));
            assertFalse(paths.has("/dev"));
            Files.delete(source.resolve("prod.java"));
            Files.delete(source.resolve("dev.java"));
        }
        Files.writeString(source.resolve("Conditional.java"), """
                package example;
                @io.quarkus.arc.properties.IfBuildProperty(name="feature", stringValue="true")
                @jakarta.ws.rs.Path("/conditional")
                class Conditional { @jakarta.ws.rs.GET public String get() { return "ok"; } }
                """);
        assertThrows(IllegalArgumentException.class, () -> generate("quarkus", "default"));
    }

    @Test void malformedAndEmptyModelsAreFailures() throws Exception {
        Path source = sources();
        assertThrows(IllegalArgumentException.class, () -> generate("spring", "default"));
        Files.writeString(source.resolve("Broken.java"), "class Broken { public void broken( }");
        assertThrows(Exception.class, () -> generate("spring", "default"));
        assertThrows(IllegalArgumentException.class, () -> MazeMain.generate(root, "spring", "/",
                "default", List.of(Path.of("../outside"))));
    }
}
