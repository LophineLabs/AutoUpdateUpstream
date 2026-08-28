package fun.bm;

import fun.bm.utils.GitExecutor;
import fun.bm.utils.GitExecutor.Result;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpstreamUpdater {
    private static final Logger LOGGER = Logger.getLogger("AutoUpdate-Updater");

    public record LatestCommit(String sha, String branch) {
    }

    private final File repoDir;
    private final String propertyKey;
    private final String upstreamUrl;
    private final String upstreamBranch;
    private final String applyTask;
    private final String buildTask;
    private final Set<String> rebuildTasks;
    private final String fixTask;
    private final GitExecutor git = new GitExecutor();

    public UpstreamUpdater(File repoDir, String propertyKey, String upstreamUrl,
                           String upstreamBranch, String applyTask, String buildTask,
                           Set<String> rebuildTasks, String fixTask) {
        this.repoDir = repoDir;
        this.propertyKey = propertyKey;
        this.upstreamUrl = upstreamUrl;
        this.upstreamBranch = upstreamBranch;
        this.applyTask = applyTask;
        this.buildTask = buildTask;
        this.rebuildTasks = rebuildTasks == null ? new LinkedHashSet<>() : rebuildTasks;
        this.fixTask = fixTask;
    }

    public void update() throws IOException, InterruptedException {
        File propertiesFile = new File(repoDir, "gradle.properties");
        if (!propertiesFile.isFile()) {
            throw new IllegalStateException("gradle.properties not found in: " + repoDir.getAbsolutePath());
        }

        String currentRef = readCurrentRef(propertiesFile);
        LOGGER.info("Current " + propertyKey + ": " + currentRef);

        LatestCommit latest = resolveLatestCommit();
        LOGGER.info("Latest upstream commit: " + latest.sha() + " (" + upstreamUrl + " @ " + latest.branch() + ")");

        if (latest.sha().equalsIgnoreCase(currentRef)) {
            LOGGER.info("Already up to date, no changes made.");
            return;
        }

        writeNewRef(propertiesFile, currentRef, latest.sha());
        LOGGER.info("Updated " + propertyKey + ": " + currentRef + " -> " + latest.sha());

        if (applyTask != null && !applyTask.isBlank()) {
            try {
                runGradleTask(applyTask);
            } catch (IllegalStateException e) {
                LOGGER.warning("Apply task '" + applyTask + "' failed: " + e.getMessage());
                LOGGER.warning("Trying fallback: rewrite and manually apply build.gradle.kts.patch...");
                if (!fallbackApplyBuildGradlePatches()) {
                    throw e;
                }
            }
        }

        // apply 完成后先执行构建（默认跳过测试），失败则跳过后续步骤直接退出
        if (buildTask != null && !buildTask.isBlank()) {
            int buildExitCode = runGradleRaw(buildTask);
            if (buildExitCode != 0) {
                LOGGER.warning("Gradle build '" + buildTask + "' failed with exit code " + buildExitCode
                        + ", skipping rebuild/fix tasks and exiting.");
                return;
            }
        }

        // 构建成功后逐个执行 rebuild 任务，重新生成补丁文件；为空则跳过，传空值可显式禁用
        for (String rebuildTask : rebuildTasks) {
            if (rebuildTask == null || rebuildTask.isBlank()) {
                continue;
            }
            runGradleTask(rebuildTask);
        }

        // 重构补丁完成后先执行修复任务（如重新生成单文件补丁）；传空值可跳过
        if (fixTask != null && !fixTask.isBlank()) {
            runGradleTask(fixTask);
        }
    }

    private String readCurrentRef(File propertiesFile) throws IOException {
        String content = Files.readString(propertiesFile.toPath(), StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(propertyKey) + "\\s*=\\s*(\\S+)\\s*$");
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            throw new IllegalStateException("Property '" + propertyKey + "' not found in gradle.properties");
        }
        return matcher.group(1);
    }

    private LatestCommit resolveLatestCommit() throws IOException, InterruptedException {
        String ref = upstreamBranch == null || upstreamBranch.isBlank() ? "HEAD" : upstreamBranch;
        Result result = git.run("ls-remote", "--symref", upstreamUrl, ref);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to query upstream via ls-remote: " + result.stderr());
        }
        String sha = null;
        String branch = ref;
        for (String line : result.stdout().split("\\R")) {
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            if (parts[0].startsWith("ref:")) {
                String target = parts[0].substring(4).trim();
                branch = target.substring(target.lastIndexOf('/') + 1);
            } else if ("HEAD".equals(parts[1]) || parts[1].endsWith("/" + ref) || parts[1].equals(ref)) {
                sha = parts[0].trim();
            }
        }
        if (sha == null) {
            throw new IllegalStateException("Cannot resolve commit sha from ls-remote output for ref '" + ref + "'.");
        }
        return new LatestCommit(sha, branch);
    }

    private void writeNewRef(File propertiesFile, String currentRef, String newRef) throws IOException {
        String content = Files.readString(propertiesFile.toPath(), StandardCharsets.UTF_8);
        String updated = content.replace(propertyKey + "=" + currentRef, propertyKey + "=" + newRef);
        if (updated.equals(content)) {
            Pattern pattern = Pattern.compile("(?m)^(\\s*" + Pattern.quote(propertyKey) + "\\s*=\\s*)\\S+\\s*$");
            updated = pattern.matcher(content).replaceFirst("$1" + newRef);
        }
        Files.writeString(propertiesFile.toPath(), updated, StandardCharsets.UTF_8);
    }

    // apply 任务失败时的兜底：在根目录一级子目录中查找 build.gradle.kts.patch，
    // 将其中的 "_," 替换为 "1," 后宽松应用（不严格校验前后文，基本匹配即视为成功）
    private boolean fallbackApplyBuildGradlePatches() throws IOException, InterruptedException {
        File[] children = repoDir.listFiles(File::isDirectory);
        if (children == null) {
            return false;
        }
        int found = 0;
        int applied = 0;
        for (File dir : children) {
            File patchFile = new File(dir, "build.gradle.kts.patch");
            if (!patchFile.isFile()) {
                continue;
            }
            found++;
            rewritePatchUnderscore(patchFile);
            if (applyPatchLoosely(dir, patchFile.getName())) {
                applied++;
            }
        }
        LOGGER.info("Fallback finished: applied " + applied + "/" + found + " build.gradle.kts.patch file(s).");
        return found > 0 && applied == found;
    }

    private void rewritePatchUnderscore(File patchFile) throws IOException {
        String content = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        String replaced = content.replace("_,", "1,");
        if (!replaced.equals(content)) {
            Files.writeString(patchFile.toPath(), replaced, StandardCharsets.UTF_8);
            LOGGER.info("Replaced '_,', '1,' in " + patchFile.getAbsolutePath());
        }
    }

    private boolean applyPatchLoosely(File dir, String patchName) throws IOException, InterruptedException {
        GitExecutor localGit = new GitExecutor(dir);
        String patchPath = new File(dir, patchName).getAbsolutePath();
        // 依次尝试：常规应用(-p1/-p0) -> 三方合并，逐步放宽匹配条件
        String[][] attempts = {
                {"apply", "--ignore-whitespace", "-p1", patchName},
                {"apply", "--ignore-whitespace", "-p0", patchName},
                {"apply", "--ignore-whitespace", "--3way", "-p1", patchName},
                {"apply", "--ignore-whitespace", "--3way", "-p0", patchName}
        };
        for (String[] attempt : attempts) {
            Result result = localGit.run(attempt);
            if (result.isSuccess()) {
                LOGGER.info("Patch applied successfully: " + patchPath);
                return true;
            }
        }
        // 最后兜底：允许部分应用（--reject），不完全校验前后文，基本符合即可
        for (String strip : new String[]{"-p1", "-p0"}) {
            Result result = localGit.run("apply", "--ignore-whitespace", "--reject", strip, patchName);
            if (result.isSuccess()) {
                LOGGER.warning("Patch applied loosely (some hunks may be rejected): " + patchPath);
                return true;
            }
        }
        LOGGER.severe("Failed to apply patch even loosely: " + patchPath);
        return false;
    }

    private void runGradleTask(String task) throws IOException, InterruptedException {
        int exitCode = runGradleRaw(task);
        if (exitCode != 0) {
            throw new IllegalStateException("Gradle task '" + task + "' failed with exit code " + exitCode
                    + ". Please resolve the issues manually.");
        }
    }

    // 执行 Gradle 命令（支持空格分隔的多个参数，如 "build -x test"），返回退出码由调用方处理
    private int runGradleRaw(String commandLine) throws IOException, InterruptedException {
        LOGGER.info("Running gradle command '" + commandLine + "'...");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        File gradlew = new File(repoDir, windows ? "gradlew.bat" : "gradlew");

        List<String> command = new ArrayList<>();
        if (windows) {
            command.add(gradlew.getAbsolutePath());
        } else {
            command.add("sh");
            command.add(gradlew.getAbsolutePath());
        }
        Collections.addAll(command, commandLine.split("\\s+"));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repoDir);
        builder.inheritIO();
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode == 0) {
            LOGGER.info("Gradle command '" + commandLine + "' completed.");
        }
        return exitCode;
    }
}
