package fun.bm.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitExecutor {
    private final File workDir;
    private final String gitBinary;
    private final long timeoutSeconds;

    public record Result(int exitCode, String stdout, String stderr) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    // 用于不需要本地仓库的命令（如 ls-remote）
    public GitExecutor() {
        this(null);
    }

    public GitExecutor(File workDir) {
        this(workDir, System.getProperty("git.binary", "git"), 60 * 20);
    }

    public GitExecutor(File workDir, String gitBinary, long timeoutSeconds) {
        this.workDir = workDir;
        this.gitBinary = gitBinary;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Result run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add(gitBinary);
        Collections.addAll(command, args);

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workDir != null) {
            builder.directory(workDir);
        }
        // 禁止交互式凭证提示，避免进程挂起
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");

        Process process = builder.start();
        StreamReader stdoutReader = new StreamReader(process.getInputStream());
        StreamReader stderrReader = new StreamReader(process.getErrorStream());
        stdoutReader.start();
        stderrReader.start();

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Git command timed out: " + String.join(" ", command));
        }
        stdoutReader.join();
        stderrReader.join();
        return new Result(process.exitValue(), stdoutReader.text(), stderrReader.text());
    }

    private static final class StreamReader extends Thread {
        private final InputStream in;
        private final StringBuilder buffer = new StringBuilder();

        StreamReader(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append(System.lineSeparator());
                }
            } catch (IOException ignored) {
            }
        }

        String text() {
            return buffer.toString().trim();
        }
    }
}
