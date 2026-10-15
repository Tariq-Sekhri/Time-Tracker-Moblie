import {spawnSync} from "node:child_process";
import {existsSync, readdirSync, readFileSync} from "node:fs";
import {fileURLToPath} from "node:url";
import path from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || (process.platform === "win32" ? path.join(process.env.LOCALAPPDATA, "Android", "Sdk") : "");
const env = {...process.env, ANDROID_HOME: sdk};
if (!env.NDK_HOME && existsSync(path.join(sdk, "ndk"))) {
    const versions = readdirSync(path.join(sdk, "ndk")).sort((a, b) => b.localeCompare(a, undefined, {numeric: true}));
    if (versions[0]) env.NDK_HOME = path.join(sdk, "ndk", versions[0]);
}
function run(command, args, cwd = root) {
    const result = spawnSync(command, args, {cwd, env, stdio: "inherit"});
    if (result.error) throw result.error;
    if (result.status !== 0) process.exit(result.status ?? 1);
}
const [mode, ...args] = process.argv.slice(2);
if (mode === "check") {
    run("cargo", ["fmt", "--manifest-path", "src-tauri/Cargo.toml", "--check"]);
    run("cargo", ["clippy", "--manifest-path", "src-tauri/Cargo.toml", "--target", "aarch64-linux-android", "--", "-D", "warnings"]);
    const project = path.join(root, "src-tauri", "gen", "android");
    if (!existsSync(path.join(project, "tauri.settings.gradle"))) throw new Error("Build an Android APK first to initialize the native dependency paths.");
    const java = env.JAVA_HOME ? path.join(env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
    run(java, ["-classpath", path.join(project, "gradle", "wrapper", "gradle-wrapper.jar"), "org.gradle.wrapper.GradleWrapperMain",
        ":app:testUniversalDebugUnitTest", ":app:lintUniversalDebug", "--console=plain"], project);
} else {
    if (!existsSync(path.join(root, "..", "time tracker", "src", "mobile", "main.tsx"))) throw new Error("Clone the matching time_tracker React repository beside this checkout as 'time tracker'.");
    if (mode === "build" && !args.includes("--debug")) {
        const propertiesPath = path.join(root, "signing", "keystore.properties");
        const properties = existsSync(propertiesPath) ? Object.fromEntries(readFileSync(propertiesPath, "utf8").split(/\r?\n/).filter(line => /^\s*[^#!\s][^=]*=/.test(line)).map(line => {
            const index = line.indexOf("="); return [line.slice(0, index).trim(), line.slice(index + 1).trim()];
        })) : {};
        for (const [key, variable] of [["storeFile", "RELEASE_STORE_FILE"], ["storePassword", "RELEASE_STORE_PASSWORD"], ["keyAlias", "RELEASE_KEY_ALIAS"], ["keyPassword", "RELEASE_KEY_PASSWORD"]]) {
            if (!(properties[key] || properties[variable] || env[variable])) throw new Error(`Release signing is missing ${key}; configure signing/keystore.properties or ${variable}.`);
        }
    }
    run(process.execPath, [path.join(root, "node_modules", "@tauri-apps", "cli", "tauri.js"), "android", mode || "build", ...args]);
}
