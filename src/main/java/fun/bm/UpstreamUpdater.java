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

    // 单文件补丁名，以及兜底时在仓库里搜索文件的最大目录深度
    private static final String PATCH_FILE_NAME = "build.gradle.kts.patch";
    private static final int MAX_SEARCH_DEPTH = 8;
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+),(\\d+|_) \\+(\\d+),(\\d+|_) @@(.*)$");

    // 记录 hunk 头占位符解析前的补丁内容，以在完全放弃时还原
    private final Map<File, String> rewriteBackup = new LinkedHashMap<>();

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
                LOGGER.warning("Trying fallback: normalize patch headers, apply patches to the upstream checkout and retry...");
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

    // apply 任务失败时的兜底：先把 hunk 头里的 "_" 行数占位符算成真实行数并重试（补丁是 Paperweight 自己读的）；
    // 仍失败则定位并恢复上游原始文件，降级应用补丁，必要时用实际生效的改动重新生成补丁文件，最后重试一次 apply。
    private boolean fallbackApplyBuildGradlePatches() throws IOException, InterruptedException {
        List<File> patchFiles = new ArrayList<>();
        walkFiles(repoDir, MAX_SEARCH_DEPTH, Set.of(".gradle", "build"), PATCH_FILE_NAME, patchFiles);
        if (patchFiles.isEmpty()) {
            LOGGER.warning("Fallback found no " + PATCH_FILE_NAME + " files under " + repoDir.getAbsolutePath());
            return false;
        }

        boolean rewritten = false;
        for (File patchFile : patchFiles) {
            if (normalizePatchHunkCounts(patchFile)) {
                rewritten = true;
            }
        }

        if (rewritten) {
            if (retryApplyTask("after resolving '_' hunk counts")) {
                return true;
            }
            // 解析后的 hunk 头仍然是合法补丁（只是把占位符换成了真实行数），保留它才能让下面的手动应用被 git 解析
            LOGGER.warning("Retrying Gradle apply failed; trying degraded (fuzz / --reject) apply of " + PATCH_FILE_NAME + "...");
        } else {
            LOGGER.warning("Trying degraded (fuzz / --reject) apply of " + PATCH_FILE_NAME + "...");
        }
        List<File> unresolved = new ArrayList<>();
        List<File> notCheckedOut = new ArrayList<>();
        int applied = 0;
        for (File patchFile : patchFiles) {
            switch (applyPatchLoosely(patchFile)) {
                case APPLIED -> applied++;
                case NOT_FOUND -> notCheckedOut.add(patchFile);
                case FAILED -> unresolved.add(patchFile);
            }
        }
        LOGGER.info("Fallback finished: applied " + applied + "/" + patchFiles.size() + " " + PATCH_FILE_NAME + " file(s), "
                + notCheckedOut.size() + " without a resolvable target.");
        for (File patchFile : notCheckedOut) {
            LOGGER.warning("Patch target not found in the working tree, leaving it to the Gradle apply task: "
                    + patchFile.getAbsolutePath());
        }
        if (!unresolved.isEmpty()) {
            for (File patchFile : unresolved) {
                LOGGER.severe("Patch could not be applied even loosely: " + patchFile.getAbsolutePath());
            }
            restoreRewrittenPatches();
            return false;
        }
        if (applied == 0) {
            LOGGER.severe("No " + PATCH_FILE_NAME + " could be applied manually, giving up.");
            restoreRewrittenPatches();
            return false;
        }
        // 上游 apply 任务是在第一个失败补丁处中断的，后面的模块可能连检出都没生成，
        // 因此把能处理的补丁处理完就重试，避免因为上游一个版本号的改动丢掉整个自动更新。
        boolean retryOk = retryApplyTask("after loosely applying " + PATCH_FILE_NAME);
        if (!retryOk && applied == patchFiles.size()) {
            LOGGER.warning("All " + PATCH_FILE_NAME + " files were applied manually but the Gradle apply task still fails;"
                    + " the remaining error is not a patch mismatch, see the Gradle output above.");
        }
        return retryOk || applied == patchFiles.size();
    }

    // 单个补丁的宽松应用结果
    private enum ApplyOutcome {
        APPLIED,
        // 补丁头指向的文件在工作区里找不到（可能源码还未检出）
        NOT_FOUND,
        // 目标已定位但没有任何方式能把补丁应用上去
        FAILED
    }

    // 递归收集仓库里的补丁文件与目标文件；skipDirs 用于跳过构建产物里的副本
    private void walkFiles(File dir, int depth, Set<String> skipDirs, String fileName, List<File> out) {
        if (depth < 0) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory()) {
                if (child.getName().equals(".git") || (skipDirs != null && skipDirs.contains(child.getName()))) {
                    continue;
                }
                walkFiles(child, depth - 1, skipDirs, fileName, out);
            } else if (child.getName().equals(fileName)) {
                out.add(child);
            }
        }
    }

    // 解析补丁头里的目标路径；保留原始形式，因为 -p 剥离的层数要按原始路径计算
    private List<String> parsePatchHeaderPaths(File patchFile) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (String line : Files.readAllLines(patchFile.toPath(), StandardCharsets.UTF_8)) {
            String raw;
            if (line.startsWith("+++ ")) {
                raw = line.substring(4);
            } else if (line.startsWith("--- ")) {
                raw = line.substring(4);
            } else if (line.startsWith("diff --git ")) {
                int marker = line.indexOf(" b/");
                if (marker < 0) {
                    continue;
                }
                raw = line.substring(marker + 3);
            } else {
                continue;
            }
            raw = raw.trim();
            int tab = raw.indexOf('\t');
            if (tab > 0) {
                // 去掉 git 在时间戳形式头里附带的日期字段
                raw = raw.substring(0, tab);
            }
            if (raw.isEmpty() || "/dev/null".equals(raw)) {
                continue;
            }
            paths.add(raw);
        }
        return new ArrayList<>(paths);
    }

    // 去掉 a/ b/ 前缀，得到用于定位真实文件的路径
    private static String normalizeHeaderPath(String headerPath) {
        int slash = headerPath.indexOf('/');
        if (slash > 0) {
            String prefix = headerPath.substring(0, slash);
            if (prefix.equals("a") || prefix.equals("b")) {
                return headerPath.substring(slash + 1);
            }
        }
        return headerPath;
    }

    // 定位补丁真正修改的文件：先按补丁头相对仓库根、相对补丁所在模块的路径查找，
    // 再按文件名在仓库里搜索。补丁文件和它修改的文件不一定同级，
    // 例如 lophine-server 里的 build.gradle.kts.patch 改的可能是 folia-server/build.gradle.kts。
    private File locatePatchTarget(File patchFile, List<String> headerPaths) {
        File moduleDir = patchFile.getParentFile();
        List<String> normalized = new ArrayList<>();
        for (String headerPath : headerPaths) {
            String path = normalizeHeaderPath(headerPath);
            if (!normalized.contains(path)) {
                normalized.add(path);
            }
        }
        for (String path : normalized) {
            File direct = new File(repoDir, path);
            if (direct.isFile()) {
                return direct;
            }
            File inModule = new File(moduleDir, path);
            if (inModule.isFile()) {
                return inModule;
            }
        }
        for (String path : normalized) {
            File found = searchByFileName(path);
            if (found != null) {
                return found;
            }
        }
        if (normalized.isEmpty() && patchFile.getName().endsWith(".patch")) {
            File beside = new File(moduleDir, patchFile.getName().substring(0, patchFile.getName().length() - ".patch".length()));
            if (beside.isFile()) {
                return beside;
            }
        }
        return null;
    }

    // 按文件名搜索：只有路径尾部与补丁头一致、或父目录名与补丁头一致时才采用，避免认错同名文件
    private File searchByFileName(String headerPath) {
        int lastSlash = headerPath.lastIndexOf('/');
        String fileName = headerPath.substring(lastSlash + 1);
        String headerParent = lastSlash < 0 ? "" : headerPath.substring(0, lastSlash);
        List<File> matches = new ArrayList<>();
        walkFiles(repoDir, MAX_SEARCH_DEPTH, null, fileName, matches);
        for (File match : matches) {
            String relative = toRepoRelative(match);
            if (relative != null && (relative.equals(headerPath) || relative.endsWith("/" + headerPath))) {
                return match;
            }
        }
        if (!headerParent.isEmpty()) {
            String parentName = headerParent.substring(headerParent.lastIndexOf('/') + 1);
            for (File match : matches) {
                File parent = match.getParentFile();
                if (parent != null && parent.getName().equals(parentName)) {
                    return match;
                }
            }
        }
        return null;
    }

    // 仓库外的路径无法用于 git apply / patch，直接当成找不到
    private String toRepoRelative(File file) {
        Path root = repoDir.toPath().toAbsolutePath().normalize();
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            return null;
        }
        return root.relativize(path).toString().replace(File.separatorChar, '/');
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

    // 只处理 hunk 头里的行数占位符。comfreyweight 生成的补丁用 "_" 表示“由应用方按正文计算行数”
    // （如 "@@ -143,_ +187,21 @@"），git apply 解析不了这种头，会直接报 "corrupt patch"。
    // 这里把真实行数算出来写回头部，得到的仍是合法补丁（应用方自己也是这么算的）。
    // 注意不能全文替换 "_,"：补丁正文里的 Kotlin 解构 { (_, value) -> } 同样含 "_,"，会被改坏。
    private boolean normalizePatchHunkCounts(File patchFile) throws IOException {
        String content = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\r?\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String header = lines[i];
            if (!header.startsWith("@@") || !header.contains("_")) {
                continue;
            }
            Matcher matcher = HUNK_HEADER.matcher(header);
            if (!matcher.matches()) {
                LOGGER.warning("Unparsable hunk header, leaving the patch as is: " + header);
                return false;
            }
            int[] counts = countHunkBody(lines, i + 1);
            String fixed = "@@ -" + matcher.group(1) + "," + resolveCount(matcher.group(2), counts[0])
                    + " +" + matcher.group(3) + "," + resolveCount(matcher.group(4), counts[1])
                    + " @@" + matcher.group(5);
            if (fixed.equals(header)) {
                continue;
            }
            lines[i] = fixed;
            changed = true;
        }
        if (!changed) {
            return false;
        }
        rewriteBackup.put(patchFile, content);
        Files.writeString(patchFile.toPath(), String.join(newline, lines), StandardCharsets.UTF_8);
        LOGGER.info("Resolved '_' line-count placeholders in hunk headers of " + patchFile.getAbsolutePath());
        return true;
    }

    private static String resolveCount(String token, int computed) {
        return "_".equals(token) ? Integer.toString(computed) : token;
    }

    // 按 hunk 正文统计旧/新侧行数：上下文行两边都算，“-”只算旧侧，“+”只算新侧
    private static int[] countHunkBody(String[] lines, int start) {
        int oldCount = 0;
        int newCount = 0;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            // 文件末尾换行号产生的空元素不是上下文行
            if (i == lines.length - 1 && line.isEmpty()) {
                break;
            }
            if (line.startsWith("@@") || line.startsWith("--- ") || line.startsWith("+++ ")
                    || line.startsWith("diff ") || line.startsWith("index ")) {
                break;
            }
            if (line.startsWith("\\")) {
                continue; // "\ No newline at end of file"
            }
            if (line.startsWith("+")) {
                newCount++;
            } else if (line.startsWith("-")) {
                oldCount++;
            } else {
                oldCount++;
                newCount++;
            }
        }
        return new int[]{oldCount, newCount};
    }

    // 放弃兜底时还原改写前的补丁，避免把未验证的改动留在工作区
    private void restoreRewrittenPatches() throws IOException {
        for (Map.Entry<File, String> entry : rewriteBackup.entrySet()) {
            Files.writeString(entry.getKey().toPath(), entry.getValue(), StandardCharsets.UTF_8);
            LOGGER.info("Restored rewritten patch: " + entry.getKey().getAbsolutePath());
        }
        rewriteBackup.clear();
    }

    // 宽松应用单个补丁：先从补丁头解析出真正被修改的文件，恢复到上游原始内容后再逐级降级应用；
    // 只有部分 hunk 能打上去时，用实际生效的改动重新生成补丁文件，让重试的 Gradle apply 能干净地应用。
    private ApplyOutcome applyPatchLoosely(File patchFile) throws IOException, InterruptedException {
        String patchPath = patchFile.getAbsolutePath();
        List<String> headerPaths = parsePatchHeaderPaths(patchFile);
        File target = locatePatchTarget(patchFile, headerPaths);
        if (target == null) {
            LOGGER.warning("Cannot locate the file patched by " + patchPath
                    + (headerPaths.isEmpty() ? "" : " (patch headers point at " + String.join(", ", headerPaths) + ")")
                    + " under " + repoDir.getAbsolutePath());
            return ApplyOutcome.NOT_FOUND;
        }

        String targetRel = toRepoRelative(target);
        if (targetRel == null) {
            LOGGER.warning("Patched file is outside the repository, skipping loose apply: " + target.getAbsolutePath());
            return ApplyOutcome.NOT_FOUND;
        }
        LOGGER.info("Patch " + patchPath + " targets " + targetRel);

        List<ApplyOption> options = buildApplyOptions(headerPaths, targetRel);
        if (options.isEmpty()) {
            LOGGER.warning("Patch headers " + String.join(", ", headerPaths)
                    + " cannot be resolved to " + targetRel + ", skipping loose apply: " + patchPath);
            return ApplyOutcome.FAILED;
        }

        // apply 任务失败时目标文件往往已经被打上部分补丁，先恢复成上游原始内容：
        // 否则 git apply 会整块失败，patch(1) 还会把整个补丁当成反向补丁全部跳过
        resetToUpstreamBaseline(target);
        byte[] pristine = Files.readAllBytes(target.toPath());
        cleanByproducts(target);

        GitExecutor repoGit = new GitExecutor(repoDir);
        if (isAlreadyApplied(repoGit, options, patchPath)) {
            return ApplyOutcome.APPLIED;
        }

        // git apply 会从仓库根解析路径，补丁头与目标文件的层级差靠 -p 和 --directory 补齐。
        // 降级顺序：完整上下文 → 只丢掉行号计数 → 少上下文匹配 → 三方合并；这些都是原子的，要么全应用要么不动。
        String[][] flagSets = {
                {"--ignore-whitespace"},
                {"--ignore-whitespace", "--recount"},
                {"--ignore-whitespace", "--recount", "-C2"},
                {"--ignore-whitespace", "--recount", "-C1"},
                {"--ignore-whitespace", "--3way"}
        };
        Result last = null;
        for (ApplyOption option : options) {
            for (int f = 0; f < flagSets.length; f++) {
                String[] flags = flagSets[f];
                last = runGitApply(repoGit, flags, option, patchPath);
                if (last.isSuccess()) {
                    LOGGER.info("Patch applied (" + describe(option) + " " + String.join(" ", flags) + "): " + patchPath);
                    // 只有第一种完整上下文匹配成功不需要重写补丁
                    return finishApplied(patchFile, pristine, target, headerPaths, f > 0, false);
                }
            }
        }

        // 部分应用：patch(1) 的 fuzz 和 git --reject 都只应用能匹配上的 hunk，取应用数量最多的一次结果
        int total = countHunks(patchFile);
        ApplyAttempt best = null;
        for (ApplyOption option : options) {
            for (int fuzz = 0; fuzz <= 3; fuzz++) {
                writeContent(target, pristine);
                Result result = runPatchCommand(patchFile, option, fuzz);
                if (result == null) {
                    break; // patch(1) 不可用
                }
                if (result.exitCode() == 0) {
                    LOGGER.info("Patch applied with patch(1) " + describe(option) + " --fuzz=" + fuzz + ": " + patchPath);
                    return finishApplied(patchFile, pristine, target, headerPaths, true, false);
                }
                best = better(best, attemptOf(target, result, total));
                last = result;
            }
        }
        // -C0 单独使用会把补丁插到错误位置，只在 --reject 里作为最后选项
        String[][] rejectFlagSets = {
                {"--ignore-whitespace", "--recount", "--reject"},
                {"--ignore-whitespace", "--recount", "-C2", "--reject"},
                {"--ignore-whitespace", "--recount", "-C0", "--reject"}
        };
        for (String[] flags : rejectFlagSets) {
            for (ApplyOption option : options) {
                writeContent(target, pristine);
                last = runGitApply(repoGit, flags, option, patchPath);
                if (last.isSuccess()) {
                    LOGGER.info("Patch applied (" + describe(option) + " " + String.join(" ", flags) + "): " + patchPath);
                    return finishApplied(patchFile, pristine, target, headerPaths, true, false);
                }
                best = better(best, attemptOf(target, last, total));
            }
        }

        if (best != null && best.applied() > 0) {
            writeContent(target, best.content());
            LOGGER.warning("Patch " + patchPath + " only matched " + best.applied() + "/" + total + " hunk(s)");
            return finishApplied(patchFile, pristine, target, headerPaths, true, true);
        }

        writeContent(target, pristine);
        LOGGER.severe("Failed to apply patch even loosely: " + patchPath
                + (last == null ? "" : ": " + firstNonBlank(last.stderr(), last.stdout())));
        return ApplyOutcome.FAILED;
    }

    // 手动应用成功后的收尾：只要用到了降级匹配（丢上下文 / fuzz / 丢 hunk），
    // 就要用「上游原文 → 实际生效内容」重新生成补丁，否则重试的 Gradle apply 仍会按旧上下文失败
    private ApplyOutcome finishApplied(File patchFile, byte[] pristine, File target, List<String> headerPaths,
                                       boolean degraded, boolean partial) throws IOException {
        cleanByproducts(target);
        if (!degraded) {
            return ApplyOutcome.APPLIED;
        }
        boolean regenerated = regeneratePatchFile(patchFile, pristine, target, headerPaths);
        LOGGER.warning((partial ? "Rejected hunks were dropped" : "Patch only matched with degraded context handling")
                + (regenerated ? "; the patch file was regenerated, review the resulting changes: "
                        : "; the patch file was left unchanged: ") + patchFile.getAbsolutePath());
        return ApplyOutcome.APPLIED;
    }

    // 一次部分应用尝试的结果：真正应用上的 hunk 数与实际写入的文件内容
    private record ApplyAttempt(int applied, int total, byte[] content) {
    }

    private static ApplyAttempt better(ApplyAttempt current, ApplyAttempt candidate) {
        if (candidate.applied() >= candidate.total()) {
            return candidate;
        }
        return current == null || candidate.applied() > current.applied() ? candidate : current;
    }

    // git apply / patch(1) 对每个成功的 hunk 会输出 "Hunk #N applied cleanly" 或 "Hunk #N succeeded at ..."
    private ApplyAttempt attemptOf(File target, Result result, int total) throws IOException {
        String text = (result.stdout() + "\n" + result.stderr()).toLowerCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("hunk #\\d+ (?:applied cleanly|succeeded at)").matcher(text);
        int applied = 0;
        while (matcher.find()) {
            applied++;
        }
        return new ApplyAttempt(applied, total, Files.readAllBytes(target.toPath()));
    }

    private static int countHunks(File patchFile) throws IOException {
        int count = 0;
        for (String line : Files.readAllLines(patchFile.toPath(), StandardCharsets.UTF_8)) {
            if (line.startsWith("@@")) {
                count++;
            }
        }
        return count;
    }

    private static void writeContent(File file, byte[] content) throws IOException {
        Files.write(file.toPath(), content);
    }

    // 清掉上一次尝试留下的 .rej/.orig，避免干扰后续判断
    private static void cleanByproducts(File target) {
        for (String suffix : new String[]{".rej", ".orig"}) {
            try {
                Files.deleteIfExists(Path.of(target.getAbsolutePath() + suffix));
            } catch (IOException ignored) {
            }
        }
    }

    // paperweight 的上游检出是独立 git 仓库（.gradle/caches/paperweight/upstreams/...），
    // 先把目标文件恢复到上游提交的内容；主仓库内的文件不动（那里可能本来就不是上游版本）
    private void resetToUpstreamBaseline(File target) throws IOException, InterruptedException {
        File root = findGitRoot(target.getParentFile());
        if (root == null || root.equals(repoDir)) {
            return;
        }
        String relative = root.toPath().toAbsolutePath().normalize()
                .relativize(target.toPath().toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
        GitExecutor upstreamGit = new GitExecutor(root);
        // 上游目录可能经过 filter 任务处理，目标文件未必在索引里；未跟踪时无处可恢复，用当前内容作为基准
        if (upstreamGit.run("ls-files", "--", relative).stdout().isBlank()) {
            LOGGER.info(relative + " is not tracked by the upstream checkout, using its current content as the baseline");
            return;
        }
        Result result = upstreamGit.run("checkout", "--", relative);
        if (result.isSuccess()) {
            LOGGER.info("Reset patch target to upstream content before re-applying: " + relative
                    + " in " + root.getAbsolutePath());
        } else {
            LOGGER.warning("Could not reset " + relative + " to upstream content: "
                    + firstNonBlank(result.stderr(), result.stdout()));
        }
    }

    private File findGitRoot(File startDir) {
        Path boundary = repoDir.toPath().toAbsolutePath().normalize();
        for (File current = startDir; current != null; current = current.getParentFile()) {
            if (new File(current, ".git").exists()) {
                return current;
            }
            Path path = current.toPath().toAbsolutePath().normalize();
            if (path.equals(boundary) || path.getParent() == null) {
                // 已经走到主仓库边界之外，不再把外部仓库当成目标所属仓库
                return null;
            }
        }
        return null;
    }

    // 上下文过期导致部分 hunk 打不上时，用「恢复后的上游内容 → 实际打上的改动」重新生成补丁，
    // 重试的 Gradle apply 就能干净地应用；代价是被拒绝 hunk 里的 Fork 改动会丢失，需要从 PR 里人工补回
    private boolean regeneratePatchFile(File patchFile, byte[] pristine, File target, List<String> headerPaths)
            throws IOException {
        Set<String> normalized = new LinkedHashSet<>();
        for (String headerPath : headerPaths) {
            normalized.add(normalizeHeaderPath(headerPath));
        }
        String label = normalized.size() == 1 ? normalized.iterator().next() : null;
        if (label == null) {
            LOGGER.warning("Patch touches " + normalized.size() + " file(s), keeping it as is: "
                    + patchFile.getAbsolutePath());
            return false;
        }
        Path pristineFile = Files.createTempFile("auu-pristine-", ".orig");
        try {
            Files.write(pristineFile, pristine);
            ProcessBuilder builder = new ProcessBuilder("diff", "-u", "-U3",
                    "--label", "a/" + label, "--label", "b/" + label,
                    pristineFile.toString(), target.getAbsolutePath());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            // diff: 0 = 无差异，1 = 有差异，>1 = 出错
            if (exitCode != 1 || output.isBlank()) {
                LOGGER.warning("diff(1) exited with " + exitCode + ", keeping the original patch: "
                        + patchFile.getAbsolutePath() + (output.isBlank() ? "" : ": " + output.trim()));
                return false;
            }
            Files.writeString(patchFile.toPath(), output, StandardCharsets.UTF_8);
            // 重新生成的补丁已经不再需要还原（否则后续 restore 会把重生成结果顶掉）
            rewriteBackup.remove(patchFile);
            LOGGER.info("Regenerated patch from the applied state: " + patchFile.getAbsolutePath());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            Files.deleteIfExists(pristineFile);
        }
    }

    // 一次路径解析组合：从补丁头剥离几层前缀、在哪个目录下解析剩下的路径
    private record ApplyOption(int strip, String directory) {
    }

    // 按 git -p 的语义枚举能把补丁头映射到目标文件的组合：剩下几层路径就作为 --directory 前缀，
    // 这样即使目标文件在缓存目录里比补丁头多几层也能打到正确位置
    private List<ApplyOption> buildApplyOptions(List<String> headerPaths, String targetRel) {
        List<ApplyOption> options = new ArrayList<>();
        for (String headerPath : headerPaths) {
            String[] parts = headerPath.split("/");
            for (int strip = 0; strip < parts.length; strip++) {
                String stripped = String.join("/", Arrays.copyOfRange(parts, strip, parts.length));
                ApplyOption option;
                if (stripped.equals(targetRel)) {
                    option = new ApplyOption(strip, "");
                } else if (targetRel.endsWith("/" + stripped)) {
                    option = new ApplyOption(strip, targetRel.substring(0, targetRel.length() - stripped.length() - 1));
                } else {
                    continue;
                }
                if (!options.contains(option)) {
                    options.add(option);
                }
            }
        }
        // 能直接按仓库根解析的组合更可靠，排在前面
        options.sort(Comparator.comparingInt(candidate -> candidate.directory().isEmpty() ? 0 : 1));
        return options;
    }

    private Result runGitApply(GitExecutor repoGit, String[] flags, ApplyOption option, String patchPath)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add("apply");
        Collections.addAll(args, flags);
        if (!option.directory().isEmpty()) {
            args.add("--directory=" + option.directory());
        }
        args.add("-p" + option.strip());
        args.add(patchPath);
        return repoGit.run(args.toArray(new String[0]));
    }

    // 上游已经带有相同改动时补丁会重复命中，这种情况无需再应用
    private boolean isAlreadyApplied(GitExecutor repoGit, List<ApplyOption> options, String patchPath)
            throws IOException, InterruptedException {
        for (ApplyOption option : options) {
            Result result = runGitApply(repoGit, new String[]{"--reverse", "--check"}, option, patchPath);
            if (result.isSuccess()) {
                LOGGER.info("Patch already applied, nothing to do: " + patchPath);
                return true;
            }
        }
        return false;
    }

    private static String describe(ApplyOption option) {
        return "-p" + option.strip() + (option.directory().isEmpty() ? "" : " --directory=" + option.directory());
    }

    // patch(1) 单个组合 + 单个 fuzz 等级；返回 null 表示 patch 不可用
    private Result runPatchCommand(File patchFile, ApplyOption option, int fuzz) {
        List<String> command = new ArrayList<>();
        command.add("patch");
        command.add("-p" + option.strip());
        command.add("-N");
        command.add("-l");
        command.add("--fuzz=" + fuzz);
        command.add("--no-backup-if-mismatch");
        command.add("-i");
        command.add(patchFile.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(option.directory().isEmpty() ? repoDir : new File(repoDir, option.directory()));
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new Result(process.waitFor(), output, "");
        } catch (IOException e) {
            LOGGER.warning("patch(1) is not available: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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
