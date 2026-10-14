package at.aau.serg.parsers;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Source recovery regressions, with independent real-Jackson wire-behavior controls. */
class MazeWireFidelityTest {
    @TempDir Path root;

    private JsonNode properties(String framework, String payload) throws Exception {
        Path source = root.resolve("src/main/java/example");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Payload.java"), "package example;\n" + payload);
        String resource = framework.equals("spring") ? """
                package example;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping("/payload")
                public class Resource {
                    @PostMapping(consumes="application/json", produces="application/json")
                    public Payload post(@RequestBody Payload body) { return body; }
                }
                """ : """
                package example;
                import jakarta.ws.rs.*;
                @Path("/payload") @Consumes("application/json") @Produces("application/json")
                public class Resource {
                    @POST public Payload post(Payload body) { return body; }
                }
                """;
        Files.writeString(source.resolve("Resource.java"), resource);
        JsonNode result = MazeMain.generate(root, framework, "/", "default",
                List.of(Path.of("src/main/java")));
        assertTrue(result.path("document").path("paths").path("/payload").has("post"));
        JsonNode properties = result.path("document").path("components").path("schemas")
                .path("Payload").path("properties");
        assertTrue(properties.isObject(), "Source payload must produce object properties");
        return properties;
    }

    @ParameterizedTest @ValueSource(strings = {"spring", "quarkus"})
    void directionalAccessFlagsAndUnrestrictedControls(String framework) throws Exception {
        JsonNode fields = properties(framework, """
                import com.fasterxml.jackson.annotation.JsonProperty;
                public class Payload {
                    @JsonProperty(value="server_id", access=JsonProperty.Access.READ_ONLY)
                    public String serverId;
                    @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String secret;
                    @JsonProperty(access=JsonProperty.Access.AUTO) public String automatic;
                    @JsonProperty(access=JsonProperty.Access.READ_WRITE) public String bidirectional;
                    public String unannotated;
                }
                """);
        assertEquals(Set.of("server_id", "secret", "automatic", "bidirectional", "unannotated"), names(fields));
        assertAll(
                () -> assertTrue(fields.path("server_id").path("readOnly").asBoolean()),
                () -> assertFalse(fields.path("server_id").path("writeOnly").asBoolean()),
                () -> assertTrue(fields.path("secret").path("writeOnly").asBoolean()),
                () -> assertFalse(fields.path("secret").path("readOnly").asBoolean()),
                () -> assertUnrestricted(fields.path("automatic")),
                () -> assertUnrestricted(fields.path("bidirectional")),
                () -> assertUnrestricted(fields.path("unannotated")));
    }

    @ParameterizedTest @ValueSource(strings = {"spring", "quarkus"})
    void explicitFalseIgnorePreservesRenamedPropertyAndTrueIgnoreWins(String framework) throws Exception {
        JsonNode fields = properties(framework, """
                import com.fasterxml.jackson.annotation.JsonIgnore;
                import com.fasterxml.jackson.annotation.JsonProperty;
                public class Payload {
                    @JsonIgnore(false) @JsonProperty("visible_name") public String visible;
                    @JsonIgnore public String hidden;
                    @JsonIgnore(true) @JsonProperty(value="hidden_alias", access=JsonProperty.Access.READ_ONLY)
                    public String ignoredDespiteRename;
                    public String ordinary;
                }
                """);
        assertEquals(Set.of("visible_name", "ordinary"), names(fields));
        assertEquals("string", fields.path("visible_name").path("type").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"spring", "quarkus"})
    void recordsContainOnlyWireComponentsAndPreserveTypes(String framework) throws Exception {
        JsonNode fields = properties(framework, """
                public record Payload(String name, int count) {
                    public String helper() { return name; }
                }
                """);
        assertEquals(Set.of("name", "count"), names(fields), "Generated Object methods are not record components");
        assertEquals("string", fields.path("name").path("type").asText());
        assertEquals("integer", fields.path("count").path("type").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"spring", "quarkus"})
    void recordComponentsRetainSupportedJacksonAnnotations(String framework) throws Exception {
        JsonNode fields = properties(framework, """
                import com.fasterxml.jackson.annotation.JsonIgnore;
                import com.fasterxml.jackson.annotation.JsonProperty;
                public record Payload(
                    @JsonProperty(value="server_id", access=JsonProperty.Access.READ_ONLY) String serverId,
                    @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String secret,
                    @JsonIgnore(false) @JsonProperty("visible_name") String visible,
                    @JsonIgnore String hidden) {}
                """);
        assertEquals(Set.of("server_id", "secret", "visible_name"), names(fields));
        assertTrue(fields.path("server_id").path("readOnly").asBoolean());
        assertTrue(fields.path("secret").path("writeOnly").asBoolean());
        assertEquals("string", fields.path("visible_name").path("type").asText());
    }

    public static class JacksonPayload {
        @JsonProperty(value="server_id", access=JsonProperty.Access.READ_ONLY)
        public String serverId = "server";
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) public String secret = "initial";
        @JsonProperty(access=JsonProperty.Access.AUTO) public String automatic = "auto";
        @JsonProperty(access=JsonProperty.Access.READ_WRITE) public String bidirectional = "both";
        @JsonIgnore(false) @JsonProperty("visible_name") public String visible = "visible";
        @JsonIgnore public String hidden = "hidden";
        @JsonIgnore(true) @JsonProperty(value="hidden_alias", access=JsonProperty.Access.READ_ONLY)
        public String ignoredDespiteRename = "hidden";
    }

    @Test void jacksonOracleConfirmsClassWireDirectionsIgnoreAndRename() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode output = mapper.valueToTree(new JacksonPayload());
        assertEquals(Set.of("server_id", "automatic", "bidirectional", "visible_name"), names(output));
        JacksonPayload read = mapper.readValue("""
                {"server_id":"attempted", "secret":"accepted", "automatic":"new-auto",
                 "bidirectional":"new-both", "visible_name":"new-visible", "hidden":"attempted",
                 "ignoredDespiteRename":"attempted"}
                """, JacksonPayload.class);
        assertAll(
                () -> assertEquals("server", read.serverId),
                () -> assertEquals("accepted", read.secret),
                () -> assertEquals("new-auto", read.automatic),
                () -> assertEquals("new-both", read.bidirectional),
                () -> assertEquals("new-visible", read.visible),
                () -> assertEquals("hidden", read.hidden),
                () -> assertEquals("hidden", read.ignoredDespiteRename));
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"hidden_alias\":\"attempted\"}", JacksonPayload.class));
    }

    public record JacksonRecord(@JsonProperty("wire_name") String name, int count,
                                @JsonIgnore String hidden) {
        public String helper() { return name; }
    }

    @Test void jacksonOracleConfirmsRecordComponentsWithoutObjectMethods() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode output = mapper.valueToTree(new JacksonRecord("item", 2, "hidden"));
        assertEquals(Set.of("wire_name", "count"), names(output));
        assertEquals("item", output.path("wire_name").asText());
        assertEquals(2, output.path("count").asInt());
        JacksonRecord read = mapper.readValue("""
                {"wire_name":"accepted", "count":3, "hidden":"attempted"}
                """, JacksonRecord.class);
        assertEquals(new JacksonRecord("accepted", 3, null), read);
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertUnrestricted(JsonNode field) {
        assertFalse(field.path("readOnly").asBoolean());
        assertFalse(field.path("writeOnly").asBoolean());
    }
}
