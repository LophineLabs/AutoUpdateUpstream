package fun.bm;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

public class Main {
    public static final Logger LOGGER = Logger.getLogger("AutoUpdate-Main");

    private static final String DEFAULT_PROPERTY_KEY = "foliaRef";
    private static final String DEFAULT_UPSTREAM_URL = "https://github.com/PaperMC/Folia.git";
    // 空表示使用上游仓库的默认分支
    private static final String DEFAULT_UPSTREAM_BRANCH = "";
    private static final String DEFAULT_APPLY_TASK = "applyAllPatches";
    private static final String DEFAULT_BUILD_TASK = "build -x test -x scanJarForBadCalls";
    private static final String DEFAULT_FIX_TASK = "rebuildFoliaSingleFilePatches";
    private static final Set<String> DEFAULT_REBUILD_TASKS = Set.of("rebuildAllServerPatches", "rebuildFoliaApiPatches", "rebuildPaperApiPatches");

    public static void main(String[] args) {
        LOGGER.info("Starting to update upstream, please wait...");

        String repoPath = System.getProperty("user.dir");
        String propertyKey = DEFAULT_PROPERTY_KEY;
        String upstreamUrl = DEFAULT_UPSTREAM_URL;
        String upstreamBranch = DEFAULT_UPSTREAM_BRANCH;
        String applyTask = DEFAULT_APPLY_TASK;
        String buildTask = DEFAULT_BUILD_TASK;
        String fixTask = DEFAULT_FIX_TASK;
        Set<String> rebuildTasks = new LinkedHashSet<>();

        // 所有参数均为可选，支持 --name=value 或 --name value 两种形式，顺序任意
        try {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--") || arg.length() <= 2) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                int eq = arg.indexOf('=');
                String name = eq >= 0 ? arg.substring(0, eq) : arg;
                String value;
                if (eq >= 0) {
                    value = arg.substring(eq + 1);
                } else if (i + 1 < args.length) {
                    value = args[++i];
                } else {
                    throw new IllegalArgumentException("Missing value for option: " + name);
                }
                switch (name) {
                    case "--repo" -> repoPath = value;
                    case "--key" -> propertyKey = value;
                    case "--url" -> upstreamUrl = value;
                    case "--branch" -> upstreamBranch = value;
                    case "--apply" -> applyTask = value;
                    case "--build" -> buildTask = value;
                    case "--fix" -> fixTask = value;
                    case "--rebuild" -> rebuildTasks.add(value);
                    default -> throw new IllegalArgumentException("Unknown option: " + name);
                }
            }
        } catch (IllegalArgumentException e) {
            LOGGER.severe(e.getMessage());
            printUsage();
            System.exit(2);
        }

        if (repoPath.isBlank()) {
            repoPath = System.getProperty("user.dir");
        }
        // 支持相对路径，统一解析为绝对路径（相对路径基于当前运行目录）
        File repoDir = Path.of(repoPath).toAbsolutePath().normalize().toFile();
        LOGGER.info("Target repository: " + repoDir.getAbsolutePath());

        if (rebuildTasks.isEmpty()) {
            rebuildTasks = DEFAULT_REBUILD_TASKS;
        }

        try {
            new UpstreamUpdater(repoDir, propertyKey, upstreamUrl, upstreamBranch, applyTask, buildTask, rebuildTasks, fixTask).update();
            LOGGER.info("Upstream update completed successfully.");
        } catch (Exception e) {
            LOGGER.severe("Upstream update failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        LOGGER.info("Usage: java -jar AutoUpdateUpstream.jar [options]");
        LOGGER.info("All options are optional (defaults shown):");
        LOGGER.info("  --repo=<path>    Target repository path (default: current working directory)");
        LOGGER.info("  --key=<name>     Property key in gradle.properties (default: " + DEFAULT_PROPERTY_KEY + ")");
        LOGGER.info("  --url=<url>      Upstream repository URL (default: " + DEFAULT_UPSTREAM_URL + ")");
        LOGGER.info("  --branch=<name>  Upstream branch to track (default: upstream default branch)");
        LOGGER.info("  --apply=<task>   Gradle task to apply changes, empty to skip (default: " + DEFAULT_APPLY_TASK + ")");
        LOGGER.info("  --build=<args>   Gradle build command run after applying, failure skips the rest and exits, empty to skip (default: " + DEFAULT_BUILD_TASK + ")");
        LOGGER.info("  --fix=<task>     Gradle fix task run after rebuilding patches, empty to skip (default: " + DEFAULT_FIX_TASK + ")");
        LOGGER.info("  --rebuild=<task> Gradle task to rebuild patches, repeatable, empty to skip (default: "
                + String.join(", ", DEFAULT_REBUILD_TASKS) + ")");
    }
}