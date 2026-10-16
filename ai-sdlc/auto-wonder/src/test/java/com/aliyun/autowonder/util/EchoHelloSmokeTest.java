package com.aliyun.autowonder.util;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工单 50466 冒烟验证：qodercli 1.1.31 MCP token 兼容性，要求执行 `echo hello`。
 */
class EchoHelloSmokeTest {

    @Test
    void shouldEchoHelloFromShell() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/c", "echo hello")
                : new ProcessBuilder("echo", "hello");
        Process process = builder.redirectErrorStream(true).start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.readLine();
        }
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, "echo hello 应正常退出");
        assertEquals("hello", output);
    }
}
