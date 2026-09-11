package com.teggr.codeagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.teggr.codeagent.docker.DockerRunnerProperties;

class GitHubRepositoryServiceTest {

    @Test
    void findsAccessibleRepositoriesAndFiltersByName() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DockerRunnerProperties properties = new DockerRunnerProperties();
        properties.setGitToken("test-token");
        GitHubRepositoryService service = new GitHubRepositoryService(builder, properties);

        server.expect(requestTo("https://api.github.com/user/repos?type=all&sort=updated&per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(queryParam("sort", "updated"))
                .andRespond(withSuccess("""
                        [{"full_name":"teggr/j2html-toolkit","html_url":"https://github.com/teggr/j2html-toolkit",
                          "visibility":"private","private":true,"updated_at":"2026-09-10T10:00:00Z"},
                         {"full_name":"teggr/other","html_url":"https://github.com/teggr/other",
                          "visibility":"public","private":false,"updated_at":"2026-09-09T10:00:00Z"}]
                        """, MediaType.APPLICATION_JSON));

        GitHubRepositoryService.RepositoryPage result = service.findRepositories("J2HTML", 1);

        assertThat(result.unavailable()).isFalse();
        assertThat(result.repositories()).singleElement().satisfies(repository -> {
            assertThat(repository.fullName()).isEqualTo("teggr/j2html-toolkit");
            assertThat(repository.htmlUrl()).isEqualTo("https://github.com/teggr/j2html-toolkit");
            assertThat(repository.visibilityLabel()).isEqualTo("private");
        });
        server.verify();
    }

        @Test
        void searchesAllPagesWhenMatchingRepositoryIsNotRecentlyUpdated() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DockerRunnerProperties properties = new DockerRunnerProperties();
        properties.setGitToken("test-token");
        GitHubRepositoryService service = new GitHubRepositoryService(builder, properties);
        String firstPage = "[" + String.join(",", java.util.Collections.nCopies(100,
            "{\"full_name\":\"teggr/other\",\"html_url\":\"https://github.com/teggr/other\","
                + "\"visibility\":\"public\",\"private\":false}")) + "]";

        server.expect(requestTo("https://api.github.com/user/repos?type=all&sort=updated&per_page=100&page=1"))
            .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/user/repos?type=all&sort=updated&per_page=100&page=2"))
            .andRespond(withSuccess("""
                [{"full_name":"fanduel/withdrawals","html_url":"https://github.com/fanduel/withdrawals",
                  "visibility":"private","private":true}]
                """, MediaType.APPLICATION_JSON));

        GitHubRepositoryService.RepositoryPage result = service.findRepositories("withdrawals", 1);

        assertThat(result.repositories()).extracting(GitHubRepositoryService.Repository::fullName)
            .containsExactly("fanduel/withdrawals");
        assertThat(result.hasMore()).isFalse();
        server.verify();
        }

    @Test
    void reportsUnavailableWhenNoGitHubTokenIsConfigured() {
        DockerRunnerProperties properties = new DockerRunnerProperties();
        GitHubRepositoryService service = new GitHubRepositoryService(RestClient.builder(), properties);

        GitHubRepositoryService.RepositoryPage result = service.findRepositories("", 1);

        assertThat(result.repositories()).isEmpty();
        assertThat(result.unavailable()).isTrue();
    }
}