package com.build.mythos

import org.junit.Assert.*
import org.junit.Test

class GitAgentPolicyTest {
    @Test fun sourceRepositoryIsAlwaysProtectedAsPushDestination() {
        assertTrue(GitAgentPolicy.isSource(RepoRef("JepriNicholee", "automatic-couscous")))
        assertTrue(GitAgentPolicy.isSource(RepoRef("jeprinicholee", "AUTOMATIC-COUSCOUS")))
        assertFalse(GitAgentPolicy.isSource(RepoRef("JepriNicholee", "automatic-couscous-2")))
    }

    @Test fun branchNamesAreCheckedBeforeSavingOrCallingApi() {
        listOf("main", "agent-work", "feature/source-reader", "release_2.0").forEach {
            assertTrue("branch should be valid: $it", GitAgentPolicy.validBranch(it))
        }
        listOf("", "@", "../main", "/main", "main/", "a..b", "a//b", "a@{b}", "bad branch", "main.lock", "foo.lock/bar", "foo\\bar").forEach {
            assertFalse("branch should be rejected: $it", GitAgentPolicy.validBranch(it))
        }
    }

    @Test fun pushPolicySkipsSecretsGeneratedAndLargeArtifactPaths() {
        assertTrue(GitAgentPolicy.safeRepoFile("app/src/main/AndroidManifest.xml"))
        assertTrue(GitAgentPolicy.safeRepoFile("gradle/wrapper/gradle-wrapper.jar"))
        assertTrue(GitAgentPolicy.safeRepoFile("app/src/main/java/com/example/TokenStore.kt"))
        assertFalse(GitAgentPolicy.safeRepoFile(".env"))
        assertFalse(GitAgentPolicy.safeRepoFile(".Git/config"))
        assertFalse(GitAgentPolicy.safeRepoFile(".ssh/id_ed25519"))
        assertFalse(GitAgentPolicy.safeRepoFile(".aws/credentials"))
        assertFalse(GitAgentPolicy.safeRepoFile("id_ecdsa"))
        assertFalse(GitAgentPolicy.safeRepoFile("local.properties"))
        assertFalse(GitAgentPolicy.safeRepoFile("keystore.properties"))
        assertFalse(GitAgentPolicy.safeRepoFile("app/release.jks"))
        assertFalse(GitAgentPolicy.safeRepoFile("build/outputs/app.apk"))
        assertFalse(GitAgentPolicy.safeRepoFile("attachments/private.txt"))
        assertFalse(GitAgentPolicy.safeRepoFile("secrets/credentials.json"))
        assertFalse(GitAgentPolicy.safeRepoFile("../../outside.txt"))
    }

    @Test fun outboundContentIsCheckedForEmbeddedCredentials() {
        assertTrue(GitAgentPolicy.containsSensitiveMaterial("token = \"ghp_abcdefghijklmnopqrstuvwxyz123456\"".toByteArray()))
        assertTrue(GitAgentPolicy.containsSensitiveMaterial("-----BEGIN PRIVATE KEY-----".toByteArray()))
        assertTrue(GitAgentPolicy.containsSensitiveMaterial("api_key=0123456789abcdef0123456789".toByteArray()))
        assertFalse(GitAgentPolicy.containsSensitiveMaterial("class MainActivity { fun start() = Unit }".toByteArray()))
        assertFalse(GitAgentPolicy.containsSensitiveMaterial(byteArrayOf(0, 1, 2, 3)))
    }

    @Test fun sourceReaderAllowsAuditOfTokenImplementationButNotSecretFiles() {
        assertTrue(GitAgentPolicy.safeSourcePath("app/src/main/java/com/example/TokenStore.kt"))
        assertTrue(GitAgentPolicy.safeSourcePath(".github/workflows/build-apk.yml"))
        assertFalse(GitAgentPolicy.safeSourcePath(".env.production"))
        assertFalse(GitAgentPolicy.safeSourcePath("keys/release.jks"))
        assertFalse(GitAgentPolicy.safeSourcePath("../../etc/passwd"))
    }
}
