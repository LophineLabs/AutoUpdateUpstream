package fun.bm;

import fun.bm.utils.GitExecutor;
import fun.bm.utils.GitExecutor.Result;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpstreamUpdater {
    private static final Logger LOGGER = Logger.getLogger("AutoUpdate-Updater");

    public record UpstreamCommit(String sha, String branch, String url) {
    }

    private final File repoDir;
    private final String propertyKey;
    private final List<String> upstreamUrls;
    private final String upstreamBranch;
    private final String applyTask;
    private final String buildTask;
    private final Set<String> rebuildTasks;
    private final String fixTask;
    private final GitExecutor git = new GitExecutor();

    public UpstreamUpdater(File repoDir, String propertyKey, List<String> upstreamUrls,
                           String upstreamBranch, String applyTask, String buildTask,
                           Set<String> rebuildTasks, String fixTask) {
        this.repoDir = repoDir;
        this.propertyKey = propertyKey;
        this.upstreamUrls = upstreamUrls;
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

        UpstreamCommit latest = resolveLatestCommit();
        LOGGER.info("Latest upstream commit: " + latest.sha() + " (" + latest.url() + " @ " + latest.branch() + ")");

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
                LOGGER.warning("Trying fallback: rewrite build.gradle.kts.patch and retry apply...");
                if (!fallbackApplyBuildGradlePatches()) {
                    throw e;
                }
            }
        }

        // apply 完成后先执行构建（默认跳过测试），失败则抛出错误，不再继续 rebuild/fix
        if (buildTask != null && !buildTask.isBlank()) {
            runGradleTask(buildTask);
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

    private UpstreamCommit resolveLatestCommit() throws IOException, InterruptedException {
        // 依次查询每个上游的最新提交，按优先级（列表顺序）从高到低处理
        List<UpstreamCommit> commits = new ArrayList<>();
        for (String url : upstreamUrls) {
            UpstreamCommit commit = queryUpstreamCommit(url);
            commits.add(commit);
            LOGGER.info("Upstream [" + url + "] latest commit: " + commit.sha() + " @ " + commit.branch());
        }

        // 只有一个上游时直接返回
        if (commits.size() == 1) {
            return commits.get(0);
        }

        // 多个上游时，创建临时仓库 fetch 所有上游后在本地比较提交新旧
        Path tempDir = Files.createTempDirectory("upstream-compare-");
        try {
            File tempRepo = tempDir.toFile();
            GitExecutor tempGit = new GitExecutor(tempRepo);

            Result initResult = tempGit.run("init");
            if (!initResult.isSuccess()) {
                throw new IllegalStateException("Failed to init temp repo for comparison: " + initResult.stderr());
            }

            // 为每个上游添加 remote 并 fetch
            for (int i = 0; i < upstreamUrls.size(); i++) {
                String name = "upstream" + i;
                Result addResult = tempGit.run("remote", "add", name, upstreamUrls.get(i));
                if (!addResult.isSuccess()) {
                    throw new IllegalStateException("Failed to add remote " + name + ": " + addResult.stderr());
                }
                Result fetchResult = tempGit.run("fetch", name, "refs/heads/*:refs/remotes/" + name + "/*");
                if (!fetchResult.isSuccess()) {
                    throw new IllegalStateException("Failed to fetch from " + upstreamUrls.get(i) + ": " + fetchResult.stderr());
                }
            }

            // 主上游优先；依次检查每个备用上游，仅当备用上游比主上游更新时才采用
            UpstreamCommit main = commits.get(0);
            for (int i = 1; i < commits.size(); i++) {
                UpstreamCommit candidate = commits.get(i);
                if (isAncestorLocal(tempGit, main.sha(), candidate.sha())) {
                    LOGGER.info("Upstream [" + candidate.url() + "] commit " + candidate.sha()
                            + " is newer than main upstream " + main.sha() + " from [" + main.url() + "], using it.");
                    return candidate;
                }
            }
            return main;
        } finally {
            // 清理临时目录
            deleteTempDir(tempDir);
        }
    }

    private UpstreamCommit queryUpstreamCommit(String url) throws IOException, InterruptedException {
        String ref = upstreamBranch == null || upstreamBranch.isBlank() ? "HEAD" : upstreamBranch;
        Result result = git.run("ls-remote", "--symref", url, ref);
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
            throw new IllegalStateException("Cannot resolve commit sha from ls-remote output for ref '" + ref + "' on " + url);
        }
        return new UpstreamCommit(sha, branch, url);
    }

    // 在本地仓库中判断 ancestorSha 是否为 candidateSha 的祖先（即 candidateSha 更新）
    private boolean isAncestorLocal(GitExecutor localGit, String ancestorSha, String candidateSha) throws IOException, InterruptedException {
        Result result = localGit.run("merge-base", "--is-ancestor", ancestorSha, candidateSha);
        return result.isSuccess();
    }

    private void deleteTempDir(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
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

    // apply 任务失败时的兜底：改写一级子目录中的 build.gradle.kts.patch，再重试 Gradle apply。
    // Paperweight 自己应用这些补丁，只改写而不重跑 apply 不会让任务恢复。
    // 仍失败时再对补丁做降级 apply（降低上下文 / fuzz），成功后再重试一次 apply。
    private boolean fallbackApplyBuildGradlePatches() throws IOException, InterruptedException {
        File[] children = repoDir.listFiles(File::isDirectory);
        if (children == null) {
            return false;
        }
        List<File> patchFiles = new ArrayList<>();
        for (File dir : children) {
            File patchFile = new File(dir, "build.gradle.kts.patch");
            if (patchFile.isFile()) {
                patchFiles.add(patchFile);
            }
        }
        if (patchFiles.isEmpty()) {
            LOGGER.warning("Fallback found no build.gradle.kts.patch files under " + repoDir.getAbsolutePath());
            return false;
        }

        boolean rewritten = false;
        for (File patchFile : patchFiles) {
            if (rewritePatchUnderscore(patchFile)) {
                rewritten = true;
            }
        }

        if (rewritten && retryApplyTask("after rewriting build.gradle.kts.patch")) {
            return true;
        }

        if (rewritten) {
            LOGGER.warning("Retrying Gradle apply failed; trying degraded (fuzz / -C0) apply of build.gradle.kts.patch...");
        } else {
            LOGGER.warning("Trying degraded (fuzz / -C0) apply of build.gradle.kts.patch...");
        }
        int applied = 0;
        for (File patchFile : patchFiles) {
            if (applyPatchLoosely(patchFile.getParentFile(), patchFile.getName())) {
                applied++;
            }
        }
        LOGGER.info("Fallback finished: applied " + applied + "/" + patchFiles.size() + " build.gradle.kts.patch file(s).");
        if (applied != patchFiles.size()) {
            return false;
        }
        retryApplyTask("after loosely applying build.gradle.kts.patch");
        return true;
    }

    private boolean retryApplyTask(String reason) throws IOException, InterruptedException {
        if (applyTask == null || applyTask.isBlank()) {
            return false;
        }
        String retryCommand = applyTask.contains("--rerun-tasks") ? applyTask : applyTask + " --rerun-tasks";
        try {
            runGradleTask(retryCommand);
            LOGGER.info("Apply task '" + applyTask + "' succeeded " + reason + ".");
            return true;
        } catch (IllegalStateException e) {
            LOGGER.warning("Apply task '" + applyTask + "' failed " + reason + ": " + e.getMessage());
            return false;
        }
    }

    private boolean rewritePatchUnderscore(File patchFile) throws IOException {
        String content = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        String replaced = content.replace("_,", "1,");
        if (replaced.equals(content)) {
            return false;
        }
        Files.writeString(patchFile.toPath(), replaced, StandardCharsets.UTF_8);
        LOGGER.info("Replaced '_,', '1,' in " + patchFile.getAbsolutePath());
        return true;
    }

    private boolean applyPatchLoosely(File dir, String patchName) throws IOException, InterruptedException {
        File patchFile = new File(dir, patchName);
        String patchPath = patchFile.getAbsolutePath();
        File target = new File(dir, "build.gradle.kts");
        if (!target.isFile()) {
            LOGGER.warning("Skipping loose git apply for " + patchPath
                    + ": no build.gradle.kts in " + dir.getAbsolutePath()
                    + " (paperweight applies this patch during '" + applyTask + "')");
            return false;
        }
        String module = dir.getName();
        GitExecutor repoGit = new GitExecutor(repoDir);

        // git apply 会从仓库根解析路径；在子目录里直接 apply 会打到错误位置。
        // 降级顺序：丢掉上下文（-C0/--recount）→ 按模块目录偏移 → 三方合并。
        String[][] attempts = {
                {"apply", "--ignore-whitespace", "--recount", "-C0", "--directory=" + module, "-p1", patchPath},
                {"apply", "--ignore-whitespace", "--recount", "-C0", "--directory=" + module, "-p0", patchPath},
                {"apply", "--ignore-whitespace", "--recount", "-C0", "-p1", patchPath},
                {"apply", "--ignore-whitespace", "--recount", "-C0", "-p0", patchPath},
                {"apply", "--ignore-whitespace", "--unidiff-zero", "--directory=" + module, "-p1", patchPath},
                {"apply", "--ignore-whitespace", "-p1", patchPath},
                {"apply", "--ignore-whitespace", "--3way", "--directory=" + module, "-p1", patchPath},
                {"apply", "--ignore-whitespace", "--3way", "-p1", patchPath}
        };
        Result last = null;
        for (String[] attempt : attempts) {
            last = repoGit.run(attempt);
            if (last.isSuccess()) {
                LOGGER.info("Patch applied successfully (" + String.join(" ", attempt) + "): " + patchPath);
                return true;
            }
        }
        if (applyWithPatchCommand(dir, patchName)) {
            LOGGER.info("Patch applied with patch(1) fuzz: " + patchPath);
            return true;
        }
        // --reject 在部分 hunk 失败时仍返回 1；出现 reject 即视为降级成功
        String[][] rejectAttempts = {
                {"apply", "--ignore-whitespace", "--recount", "-C0", "--reject", "--directory=" + module, "-p1", patchPath},
                {"apply", "--ignore-whitespace", "--recount", "-C0", "--reject", "-p1", patchPath},
                {"apply", "--ignore-whitespace", "--recount", "-C0", "--reject", "-p0", patchPath}
        };
        for (String[] attempt : rejectAttempts) {
            last = repoGit.run(attempt);
            if (last.isSuccess() || isPartialRejectApply(last)) {
                LOGGER.warning("Patch applied loosely (some hunks may be rejected): " + patchPath);
                return true;
            }
        }
        LOGGER.severe("Failed to apply patch even loosely: " + patchPath
                + (last == null ? "" : ": " + firstNonBlank(last.stderr(), last.stdout())));
        return false;
    }

    private boolean applyWithPatchCommand(File dir, String patchName) throws IOException, InterruptedException {
        for (String strip : new String[]{"-p1", "-p0", "-p2"}) {
            List<String> command = new ArrayList<>();
            command.add("patch");
            command.add(strip);
            command.add("-N");
            command.add("-l");
            command.add("--fuzz=3");
            command.add("--no-backup-if-mismatch");
            command.add("-i");
            command.add(patchName);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir);
            builder.redirectErrorStream(true);
            try {
                Process process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    return true;
                }
                LOGGER.warning("patch(1) " + strip + " failed for " + patchName + " (exit " + exitCode + "): " + output);
            } catch (IOException e) {
                LOGGER.warning("patch(1) is not available: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    private static boolean isPartialRejectApply(Result result) {
        if (result == null || result.exitCode() != 1) {
            return false;
        }
        String text = (result.stdout() + "\n" + result.stderr()).toLowerCase(Locale.ROOT);
        return text.contains("with rejects") || text.contains(".rej");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
