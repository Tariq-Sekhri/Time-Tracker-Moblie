import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const kinds = {ma: "major", major: "major", mi: "minor", minor: "minor", p: "patch", patch: "patch"};
const kind = kinds[process.argv[2]?.toLowerCase()];
if (!kind) throw new Error("Usage: npm run bump -- <major|minor|patch>");
const configPath = path.join(root, "src-tauri", "tauri.conf.json");
const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
const previous = config.version;
const parts = previous.split(".").map(Number);
if (parts.length !== 3 || parts.some(value => !Number.isInteger(value))) throw new Error("Expected a three-part mobile version");
if (kind === "major") { parts[0]++; parts[1] = 0; parts[2] = 0; }
if (kind === "minor") { parts[1]++; parts[2] = 0; }
if (kind === "patch") parts[2]++;
const version = parts.join(".");
config.version = version;
config.bundle.android.versionCode++;
const files = new Map([[configPath, JSON.stringify(config, null, 2) + "\n"]]);
for (const filename of ["package.json", "package-lock.json"]) {
    const target = path.join(root, filename);
    const value = JSON.parse(fs.readFileSync(target, "utf8"));
    value.version = version;
    if (value.packages?.[""]) value.packages[""].version = version;
    files.set(target, JSON.stringify(value, null, 2) + "\n");
}
const cargoPath = path.join(root, "src-tauri", "Cargo.toml");
files.set(cargoPath, fs.readFileSync(cargoPath, "utf8").replace(/^version = "[^"]+"/m, `version = "${version}"`));
const lockPath = path.join(root, "src-tauri", "Cargo.lock");
files.set(lockPath, fs.readFileSync(lockPath, "utf8").replace(/(name = "time-tracker-mobile"\r?\nversion = ")[^"]+/, `$1${version}`));
for (const [filename, contents] of files) fs.writeFileSync(filename, contents);
console.log(`Mobile ${previous} -> ${version}; Android versionCode ${config.bundle.android.versionCode}`);
