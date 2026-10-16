package com.aliyun.autowonder.ai.engine;

import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoWorkspacePreparerTest {

    @Test
    void buildCloneCommandUsesDepthOneAndDefaultBranch() {
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(mock(RepoDao.class), "git", 120);
        RepoDO repo = new RepoDO();
        repo.setUrl("git@gitlab.alibaba-inc.com:sdlc-autopilot/auto-wonder.git");
        repo.setDefaultBranch("master");

        List<String> command = preparer.buildCloneCommand(repo, Path.of("/tmp/aiw/10003/repo"));

        assertEquals(List.of("git", "clone", "--depth", "1", "--branch", "master",
                "--single-branch", "git@gitlab.alibaba-inc.com:sdlc-autopilot/auto-wonder.git",
                Path.of("/tmp/aiw/10003/repo").toString()), command);
    }

    @Test
    void prepareMultiRepoReturnsWorkDirWhenNoRepos(@TempDir Path tempDir) throws IOException {
        RepoDao repoDao = mock(RepoDao.class);
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of());
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, "git", 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), result);
    }

    @Test
    void prepareMultiRepoSkipsRepoWithEmptyUrl(@TempDir Path tempDir) throws IOException {
        RepoDao repoDao = mock(RepoDao.class);
        RepoDO repo = new RepoDO();
        repo.setId(1L);
        repo.setName("no-url-repo");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, "git", 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir);

        assertTrue(Files.exists(result));
        assertEquals("repos", result.getFileName().toString());
    }

    @Test
    void prepareMultiRepoReadsCloneProcessStreamsWithProjectThreadPool(@TempDir Path tempDir) throws Exception {
        Path fakeGit = writeExecutable(tempDir, "fake-git", """
                #!/bin/sh
                target=""
                for arg in "$@"; do
                  target="$arg"
                done
                mkdir -p "$target"
                echo clone-output
                echo clone-diagnostic >&2
                exit 0
                """, """
                @echo off
                setlocal EnableExtensions
                set "target="
                :loop
                if "%~1"=="" goto done
                set "target=%~1"
                shift
                goto loop
                :done
                if not exist "%target%" mkdir "%target%"
                echo clone-output
                echo clone-diagnostic 1>&2
                exit /b 0
                """);
        RepoDao repoDao = mock(RepoDao.class);
        RepoDO repo = new RepoDO();
        repo.setId(7L);
        repo.setName("main-repo");
        repo.setUrl("https://github.com/workspace/repo.git");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, fakeGit.toString(), 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir.resolve("work"));

        assertEquals("repos", result.getFileName().toString());
        assertTrue(Files.isDirectory(result.resolve("main-repo")));
    }

    @Test
    void cloneUsesLocalGitPermissionWithoutWritingCredentialFiles(@TempDir Path tempDir) throws Exception {
        Path envDump = tempDir.resolve("env-dump.txt");
        String envFile = envDump.toString().replace("\"", "");
        Path fakeGit = writeExecutable(tempDir, "env-probe-git", """
                #!/bin/sh
                target=""
                for arg in "$@"; do
                  target="$arg"
                done
                mkdir -p "$target"
                {
                  echo "GIT_SSH_COMMAND=$GIT_SSH_COMMAND"
                  echo "GIT_ASKPASS=$GIT_ASKPASS"
                  echo "AUTOWONDER_REPO_TOKEN=$AUTOWONDER_REPO_TOKEN"
                  echo "GIT_TERMINAL_PROMPT=$GIT_TERMINAL_PROMPT"
                } > "%s"
                exit 0
                """.formatted(envDump), """
                @echo off
                setlocal EnableExtensions
                set "target="
                :loop
                if "%%~1"=="" goto write
                set "target=%%~1"
                shift
                goto loop
                :write
                if not exist "%%target%%" mkdir "%%target%%"
                > "%s" echo GIT_SSH_COMMAND=%%GIT_SSH_COMMAND%%
                >> "%s" echo GIT_ASKPASS=%%GIT_ASKPASS%%
                >> "%s" echo AUTOWONDER_REPO_TOKEN=%%AUTOWONDER_REPO_TOKEN%%
                >> "%s" echo GIT_TERMINAL_PROMPT=%%GIT_TERMINAL_PROMPT%%
                exit /b 0
                """.formatted(envFile, envFile, envFile, envFile));
        RepoDao repoDao = mock(RepoDao.class);
        RepoDO repo = new RepoDO();
        repo.setId(7L);
        repo.setName("main-repo");
        repo.setUrl("git@github.com:workspace/private.git");
        when(repoDao.list(100L, 0, 100)).thenReturn(List.of(repo));
        RepoWorkspacePreparer preparer = new RepoWorkspacePreparer(repoDao, fakeGit.toString(), 120);

        Path result = preparer.prepareMultiRepo(100L, tempDir.resolve("work"));

        String env = Files.readString(envDump).replace("\r\n", "\n");
        assertTrue(env.contains("GIT_SSH_COMMAND=\n"), env);
        assertTrue(env.contains("GIT_ASKPASS=\n"), env);
        assertTrue(env.contains("AUTOWONDER_REPO_TOKEN=\n"), env);
        assertTrue(env.contains("GIT_TERMINAL_PROMPT=0"), env);
        assertFalse(Files.exists(result.resolve("repo_key")));
        assertFalse(Files.exists(result.resolve("git-askpass.sh")));
    }

    private static Path writeExecutable(Path dir, String baseName, String shellScript, String windowsCommand)
            throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = dir.resolve(baseName + (windows ? ".cmd" : ".sh"));
        Files.writeString(script, (windows ? windowsCommand : shellScript).stripIndent(), StandardCharsets.UTF_8);
        if (!windows) {
            assertTrue(script.toFile().setExecutable(true, true));
        }
        return script;
    }
}
