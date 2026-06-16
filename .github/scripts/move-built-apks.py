from pathlib import Path
import shutil
import os

# Ensure we're in the correct directory (main)
print(f"Current directory: {os.getcwd()}")

REPO_APK_DIR = Path("repo/apk")

print(f"Cleaning up: {REPO_APK_DIR.absolute()}")
shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

artifact_dir = Path.home().joinpath("apk-artifacts")

print(f"Looking for APKs in: {artifact_dir.absolute()}")

if not artifact_dir.exists():
    print(f"Error: Artifact directory not found: {artifact_dir}")
    exit(1)

apks = list(artifact_dir.glob("**/*.apk"))
print(f"Found {len(apks)} APKs")

if not apks:
    print("Error: No APKs found in artifacts")
    exit(1)

for apk in apks:
    apk_name = apk.name.replace("-release.apk", ".apk")
    target = REPO_APK_DIR.joinpath(apk_name)
    print(f"Moving: {apk} -> {target}")
    shutil.move(str(apk), str(target))

# Clean up the source directory after moving files
shutil.rmtree(artifact_dir, ignore_errors=True)

print(f"Successfully moved {len(apks)} APKs to {REPO_APK_DIR.absolute()}")
print(f"APKs in repo/apk:")
for apk in REPO_APK_DIR.iterdir():
    print(f"  - {apk.name}")
