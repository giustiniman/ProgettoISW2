package it.uniroma2.isw2.openjpa.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uniroma2.isw2.openjpa.model.RawRelease;
import it.uniroma2.isw2.openjpa.model.Ticket;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class JiraClient {

    private static final Pattern STRICT_SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final DateTimeFormatter JIRA_WITH_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final DateTimeFormatter JIRA_WITHOUT_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    private final String baseUrl;
    private final String project;
    private final HttpClient http;
    private final ObjectMapper json;

    public JiraClient(String baseUrl, String project) {
        this.baseUrl = baseUrl;
        this.project = project;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        this.json = new ObjectMapper();
    }

    public List<RawRelease> fetchReleasedSemanticVersions() throws IOException, InterruptedException {
        JsonNode root = get(baseUrl + "/rest/api/2/project/" + encode(project));
        List<RawRelease> releases = new ArrayList<>();
        for (JsonNode version : root.path("versions")) {
            String name = version.path("name").asText("").trim();
            String date = version.path("releaseDate").asText("").trim();
            if (!version.path("released").asBoolean(false)
                    || !STRICT_SEMVER.matcher(name).matches()
                    || date.isEmpty()) {
                continue;
            }
            try {
                releases.add(new RawRelease(version.path("id").asText(""), name, LocalDate.parse(date)));
            } catch (DateTimeParseException ignored) {
                // Una release senza data ISO valida non e' utilizzabile.
            }
        }
        return releases;
    }

    public List<Ticket> fetchFixedBugs() throws IOException, InterruptedException {
        List<Ticket> result = new ArrayList<>();
        int startAt = 0;
        int total = 1;
        String jql = "project=\"" + project + "\" AND issuetype=Bug "
                + "AND status in (Resolved, Closed) AND resolution=Fixed";

        while (startAt < total) {
            String url = baseUrl + "/rest/api/2/search?jql=" + encode(jql)
                    + "&fields=key,created,resolutiondate,versions,fixVersions"
                    + "&startAt=" + startAt + "&maxResults=1000";
            JsonNode page = get(url);
            total = page.path("total").asInt(0);
            JsonNode issues = page.path("issues");
            if (!issues.isArray() || issues.isEmpty()) {
                break;
            }
            for (JsonNode issue : issues) {
                JsonNode fields = issue.path("fields");
                Instant created = parseJiraInstant(fields.path("created").asText(null));
                Instant resolved = parseJiraInstant(fields.path("resolutiondate").asText(null));
                if (created == null || resolved == null) {
                    continue;
                }
                result.add(new Ticket(
                        issue.path("key").asText(),
                        created,
                        resolved,
                        versionNames(fields.path("versions")),
                        versionNames(fields.path("fixVersions"))
                ));
            }
            startAt += issues.size();
        }
        return result;
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "ISW2-OpenJPA-Milestone1/1.0")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Jira ha risposto HTTP " + response.statusCode() + " per " + url);
        }
        return json.readTree(response.body());
    }

    private static List<String> versionNames(JsonNode versions) {
        List<String> names = new ArrayList<>();
        if (versions.isArray()) {
            for (JsonNode version : versions) {
                String name = version.path("name").asText("").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    static Instant parseJiraInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_OFFSET_DATE_TIME, JIRA_WITH_MILLIS, JIRA_WITHOUT_MILLIS)) {
            try {
                return OffsetDateTime.parse(value, formatter).toInstant();
            } catch (DateTimeParseException ignored) {
                // Prova il formato successivo.
            }
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
