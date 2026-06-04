package com.cicd.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitUtilsTest {

    @Test
    void testExtractRepoNameFromHttpsUrl() {
        String url = "https://git.example.com/group/project.git";

        String repoName = GitUtils.extractRepoName(url);

        assertEquals("project", repoName);
    }

    @Test
    void testExtractRepoNameFromSshUrl() {
        String url = "git@git.example.com:group/project.git";

        String repoName = GitUtils.extractRepoName(url);

        assertEquals("project", repoName);
    }

    @Test
    void testExtractRepoNameWithoutGitSuffix() {
        String url = "https://git.example.com/group/project";

        String repoName = GitUtils.extractRepoName(url);

        assertEquals("project", repoName);
    }

    @Test
    void testExtractRepoNameWithNestedGroups() {
        String url = "https://git.example.com/group1/group2/group3/project.git";

        String repoName = GitUtils.extractRepoName(url);

        assertEquals("project", repoName);
    }

    @Test
    void testExtractOwnerFromHttpsUrl() {
        String url = "https://git.example.com/group/project.git";

        String owner = GitUtils.extractOwner(url);

        assertEquals("group", owner);
    }

    @Test
    void testExtractOwnerFromSshUrl() {
        String url = "git@git.example.com:group/project.git";

        String owner = GitUtils.extractOwner(url);

        assertEquals("group", owner);
    }

    @Test
    void testExtractOwnerWithNestedGroups() {
        String url = "https://git.example.com/group1/group2/project.git";

        String owner = GitUtils.extractOwner(url);

        assertEquals("group1/group2", owner);
    }

    @Test
    void testExtractDomain() {
        String httpsUrl = "https://git.example.com/group/project.git";
        String sshUrl = "git@git.example.com:group/project.git";

        assertEquals("git.example.com", GitUtils.extractDomain(httpsUrl));
        assertEquals("git.example.com", GitUtils.extractDomain(sshUrl));
    }

    @Test
    void testExtractDomainWithPort() {
        String url = "https://git.example.com:8443/group/project.git";

        assertEquals("git.example.com", GitUtils.extractDomain(url));
    }

    @Test
    void testIsValidGitUrl() {
        assertTrue(GitUtils.isValidGitUrl("https://git.example.com/group/project.git"));
        assertTrue(GitUtils.isValidGitUrl("git@git.example.com:group/project.git"));
        assertTrue(GitUtils.isValidGitUrl("http://git.example.com/group/project.git"));
        assertTrue(GitUtils.isValidGitUrl("ssh://git@git.example.com:2222/group/project.git"));
        assertFalse(GitUtils.isValidGitUrl("not-a-git-url"));
        assertFalse(GitUtils.isValidGitUrl("ftp://example.com/repo.git"));
        assertFalse(GitUtils.isValidGitUrl(null));
        assertFalse(GitUtils.isValidGitUrl(""));
    }

    @Test
    void testNormalizeUrl() {
        String httpsUrl = "https://git.example.com/group/project.git";
        String sshUrl = "git@git.example.com:group/project.git";

        assertEquals("git.example.com/group/project", GitUtils.normalizeUrl(httpsUrl));
        assertEquals("git.example.com/group/project", GitUtils.normalizeUrl(sshUrl));
    }

    @Test
    void testMatchesBranchPattern() {
        assertTrue(GitUtils.matchesBranchPattern("release/1.0.0", "release/*"));
        assertTrue(GitUtils.matchesBranchPattern("feature/new-ui", "feature/*"));
        assertTrue(GitUtils.matchesBranchPattern("main", "main"));
        assertTrue(GitUtils.matchesBranchPattern("develop", "develop"));
        assertTrue(GitUtils.matchesBranchPattern("hotfix/1.0.1", "hotfix/*"));
        assertFalse(GitUtils.matchesBranchPattern("feature/new-ui", "release/*"));
        assertFalse(GitUtils.matchesBranchPattern("main", "release/*"));
        assertTrue(GitUtils.matchesBranchPattern("release/1.0.0", "*"));
    }

    @Test
    void testMatchesBranchPatternWithMultiplePatterns() {
        String[] patterns = {"release/*", "hotfix/*", "main"};

        assertTrue(GitUtils.matchesBranchPattern("release/1.0.0", patterns));
        assertTrue(GitUtils.matchesBranchPattern("hotfix/1.0.1", patterns));
        assertTrue(GitUtils.matchesBranchPattern("main", patterns));
        assertFalse(GitUtils.matchesBranchPattern("feature/new-ui", patterns));
    }

    @Test
    void testGenerateWebhookSecret() {
        String secret1 = GitUtils.generateWebhookSecret();
        String secret2 = GitUtils.generateWebhookSecret();

        assertNotNull(secret1);
        assertNotNull(secret2);
        assertNotEquals(secret1, secret2);
        assertEquals(32, secret1.length());
    }

    @Test
    void testExtractShortCommitSha() {
        String fullSha = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

        String shortSha = GitUtils.extractShortCommitSha(fullSha);

        assertEquals("a1b2c3d", shortSha);
    }

    @Test
    void testExtractShortCommitShaShortInput() {
        String shortSha = "a1b2c3d";

        assertEquals("a1b2c3d", GitUtils.extractShortCommitSha(shortSha));
    }

    @Test
    void testIsValidCommitSha() {
        assertTrue(GitUtils.isValidCommitSha("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"));
        assertTrue(GitUtils.isValidCommitSha("A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6E7F8A9B0"));
        assertTrue(GitUtils.isValidCommitSha("a1b2c3d"));
        assertFalse(GitUtils.isValidCommitSha("not-a-valid-sha"));
        assertFalse(GitUtils.isValidCommitSha(""));
        assertFalse(GitUtils.isValidCommitSha(null));
        assertFalse(GitUtils.isValidCommitSha("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c"));
    }
}
