package com.teggr.codeagent.web;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.teggr.codeagent.docker.DockerRunnerProperties;

/** Retrieves the repositories available to the token used by runner containers. */
@Service
public class GitHubRepositoryService {

    static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final DockerRunnerProperties runnerProperties;

    public GitHubRepositoryService(RestClient.Builder restClientBuilder, DockerRunnerProperties runnerProperties) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.runnerProperties = runnerProperties;
    }

    public RepositoryPage findRepositories(String query, int page) {
        String gitToken = runnerProperties.getGitToken();
        if (gitToken == null || gitToken.isBlank()) {
            return RepositoryPage.unavailablePage();
        }

        try {
            boolean searching = query != null && !query.isBlank();
            int requestedPage = Math.max(page, 1);
            List<GitHubRepository> response = searching
                ? fetchAllRepositories(gitToken)
                : fetchPage(gitToken, requestedPage);

            List<Repository> repositories = (response == null ? List.<GitHubRepository>of() : response).stream()
                    .map(repository -> new Repository(repository.fullName(), repository.htmlUrl(),
                            repository.visibility(), repository.isPrivate(), repository.updatedAt()))
                    .filter(repository -> matches(repository, query))
                    .toList();
            return new RepositoryPage(repositories, !searching && response != null && response.size() == PAGE_SIZE,
                    false);
        } catch (RestClientException exception) {
            return RepositoryPage.unavailablePage();
        }
    }

    private List<GitHubRepository> fetchPage(String gitToken, int page) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/user/repos")
                    .queryParam("type", "all")
                        .queryParam("sort", "updated")
                        .queryParam("per_page", PAGE_SIZE)
                        .queryParam("page", page)
                        .build())
                .header("Authorization", "Bearer " + gitToken)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<GitHubRepository>>() { });
    }

    private List<GitHubRepository> fetchAllRepositories(String gitToken) {
        List<GitHubRepository> repositories = new java.util.ArrayList<>();
        int page = 1;
        while (true) {
            List<GitHubRepository> response = fetchPage(gitToken, page);
            if (response == null || response.isEmpty()) {
                return repositories;
            }
            repositories.addAll(response);
            if (response.size() < PAGE_SIZE) {
                return repositories;
            }
            page++;
        }
    }

    private boolean matches(Repository repository, String query) {
        return query == null || query.isBlank()
                || repository.fullName().toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }

    public record Repository(String fullName, String htmlUrl, String visibility, boolean isPrivate, Instant updatedAt) {
        public String visibilityLabel() {
            return visibility == null || visibility.isBlank() ? (isPrivate ? "Private" : "Public") : visibility;
        }
    }

    public record RepositoryPage(List<Repository> repositories, boolean hasMore, boolean unavailable) {
        public static RepositoryPage unavailablePage() {
            return new RepositoryPage(List.of(), false, true);
        }
    }

    private record GitHubRepository(@JsonProperty("full_name") String fullName,
            @JsonProperty("html_url") String htmlUrl, String visibility, @JsonProperty("private") boolean isPrivate,
            @JsonProperty("updated_at") Instant updatedAt) {
    }
}