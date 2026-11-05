package org.yggdrasil.scheduler.builder;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministically maps a subject question to the primary stewards of matching
 * subheadings. This is routing assistance, not a model call or authority grant.
 */
public final class BuilderSubjectResolver {
    private final JSONObject registry;

    public static final class Resolution {
        public final boolean matched;
        public final boolean ambiguous;
        public final String subjectId;
        public final String heading;
        public final List<String> subheadingIds;
        public final List<BuilderRole> selectedRoles;

        private Resolution(boolean matched, boolean ambiguous, String subjectId,
                           String heading, List<String> subheadingIds,
                           List<BuilderRole> selectedRoles) {
            this.matched = matched;
            this.ambiguous = ambiguous;
            this.subjectId = subjectId;
            this.heading = heading;
            this.subheadingIds = Collections.unmodifiableList(new ArrayList<>(subheadingIds));
            this.selectedRoles = Collections.unmodifiableList(new ArrayList<>(selectedRoles));
        }
    }

    public BuilderSubjectResolver(JSONObject registry) {
        if (registry == null) throw new IllegalArgumentException("subject registry is required");
        this.registry = registry;
    }

    public static BuilderSubjectResolver fromAssets(AssetManager assets)
            throws IOException, JSONException {
        if (assets == null) throw new IllegalArgumentException("assets are required");
        try (InputStream input = assets.open("registry.json");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            return new BuilderSubjectResolver(new JSONObject(json.toString()));
        }
    }

    public Resolution resolve(String question) throws JSONException {
        String query = normalize(question);
        if (query.isEmpty()) return emptyResolution(false);

        JSONArray subjects = registry.optJSONArray("subjects");
        if (subjects == null) throw new JSONException("subject registry has no subjects array");

        List<JSONObject> matches = new ArrayList<>();
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject != null && matchesSubject(subject, query)) matches.add(subject);
        }
        if (matches.isEmpty()) return emptyResolution(false);
        if (matches.size() > 1) return emptyResolution(true);

        JSONObject subject = matches.get(0);
        List<String> matchedSubheadings = new ArrayList<>();
        Set<BuilderRole> selected = new HashSet<>();
        JSONArray subheadings = subject.optJSONArray("subheadings");
        if (subheadings != null) {
            for (int i = 0; i < subheadings.length(); i++) {
                JSONObject subheading = subheadings.optJSONObject(i);
                if (subheading == null || !matchesSubheading(subheading, query)) continue;
                String id = subheading.optString("subheading_id", "");
                if (!id.isEmpty()) matchedSubheadings.add(id);
                addRoles(subheading.optJSONArray("primary_stewards"), selected);
            }
        }

        // A broad subject-heading request assembles all of that subject's stewards.
        // A narrower question assembles only the stewards for matched subheadings.
        if (matchedSubheadings.isEmpty()) {
            addRoles(subject.optJSONArray("stewardship"), selected);
        }
        if (selected.isEmpty())
            throw new JSONException("matched subject has no valid primary stewards");

        List<BuilderRole> ordered = new ArrayList<>();
        for (BuilderRole role : BuilderRole.values()) {
            if (selected.contains(role)) ordered.add(role);
        }
        return new Resolution(true, false,
                subject.optString("subject_id", ""),
                subject.optString("heading", ""),
                matchedSubheadings, ordered);
    }

    private boolean matchesSubject(JSONObject subject, String query) throws JSONException {
        if (contains(query, subject.optString("heading", ""))
                || contains(query, subject.optString("subject_id", "").replace('.', ' '))) return true;
        if (matchesTerms(query, subject.optJSONArray("recognition_terms"))) return true;
        JSONArray subheadings = subject.optJSONArray("subheadings");
        if (subheadings != null) {
            for (int i = 0; i < subheadings.length(); i++) {
                JSONObject subheading = subheadings.optJSONObject(i);
                if (subheading != null && matchesSubheading(subheading, query)) return true;
            }
        }
        return false;
    }

    private static boolean matchesSubheading(JSONObject subheading, String query)
            throws JSONException {
        return contains(query, subheading.optString("heading", ""))
                || matchesTerms(query, subheading.optJSONArray("recognition_terms"));
    }

    private static boolean matchesTerms(String query, JSONArray terms) {
        if (terms == null) return false;
        for (int i = 0; i < terms.length(); i++) {
            if (contains(query, terms.optString(i, ""))) return true;
        }
        return false;
    }

    private static boolean contains(String normalizedQuery, String term) {
        String normalizedTerm = normalize(term);
        return !normalizedTerm.isEmpty()
                && (" " + normalizedQuery + " ").contains(" " + normalizedTerm + " ");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim().replaceAll("\\s+", " ");
    }

    private static void addRoles(JSONArray entries, Set<BuilderRole> selected)
            throws JSONException {
        if (entries == null) return;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            String id = entry == null ? entries.optString(i, "") : entry.optString("builder_id", "");
            try {
                selected.add(BuilderRole.valueOf(id));
            } catch (IllegalArgumentException invalidRole) {
                throw new JSONException("unknown Builder ID in subject registry: " + id);
            }
        }
    }

    private static Resolution emptyResolution(boolean ambiguous) {
        return new Resolution(false, ambiguous, "", "", Collections.emptyList(),
                Collections.emptyList());
    }
}
