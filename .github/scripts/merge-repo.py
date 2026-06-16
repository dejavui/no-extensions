import html
import sys
import json
import os
from pathlib import Path
import re
import shutil

from google.protobuf import json_format

import index_pb2

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath("main/repo")

to_delete: list[str] = json.loads(sys.argv[1])

REPO_NAME = os.getenv("REPO_NAME", "not-extensions")
SITE_NAME = os.getenv("SITE_NAME", "dejavui")
SITE_URL = os.getenv("SITE_URL", "https://dejavui.github.io")
GITHUB_REPOSITORY_OWNER = os.getenv("GITHUB_REPOSITORY_OWNER", "dejavui")

for module in to_delete:
    apk_name = f"tachiyomi-{module}-v*.*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.extension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(file.name)
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(file.name)
        file.unlink(missing_ok=True)

shutil.copytree(src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok = True)
shutil.copytree(src=LOCAL_REPO.joinpath("icon"), dst=REMOTE_REPO.joinpath("icon"), dirs_exist_ok = True)

REMOTE_REPO.joinpath("index.json").unlink(missing_ok=True)

remote_index = []
if REMOTE_REPO.joinpath("index.min.json").exists():
    with REMOTE_REPO.joinpath("index.min.json").open() as remote_index_file:
        content = remote_index_file.read()
        if content.strip():
            remote_index = json.loads(content)

local_index = []
if LOCAL_REPO.joinpath("index.min.json").exists():
    with LOCAL_REPO.joinpath("index.min.json").open() as local_index_file:
        content = local_index_file.read()
        if content.strip():
            local_index = json.loads(content)

legacy_index = [
    item for item in remote_index
    if not any([item["pkg"].endswith(f".{module}") for module in to_delete])
]
legacy_index.extend(local_index)
legacy_index.sort(key=lambda x: x["pkg"])

def extract_extension_lib(version: str) -> str:
    if match := re.search(r'(\d+)\.(\d+)', version):
        return f"{match.group(1)}.{match.group(2)}"

    raise ValueError(f"Version {version} doesn't contain MAJOR.MINOR")

index = index_pb2.Index(
    name = SITE_NAME,
    badgeLabel = SITE_NAME[:4].upper(),
    signingKey = os.getenv("SIGNING_KEY_HASH", "d6dffd97b13fb936bf7ff894b2963b713eebfb0e6daa04be2190969f5ddb1936"),
    contact=index_pb2.Contact(
        website=SITE_URL,
    ),
    extensions=[
        index_pb2.Extension(
            name=extension["name"].replace("Tachiyomi: ", ""),
            packageName=extension["pkg"],
            resources=index_pb2.Resources(
                apkUrl=f"https://raw.githubusercontent.com/{GITHUB_REPOSITORY_OWNER}/{REPO_NAME}/refs/heads/repo/apk/{extension["apk"]}",
                iconUrl=f"https://raw.githubusercontent.com/{GITHUB_REPOSITORY_OWNER}/{REPO_NAME}/refs/heads/repo/icon/{extension["pkg"]}.png",
            ),
            extensionLib=extract_extension_lib(extension["version"]),
            versionCode=extension["code"],
            versionName=extension["version"],
            sources=[
                index_pb2.Source(
                    id=int(source["id"]),
                    name=source["name"],
                    language=source["lang"],
                    homeUrl=source["baseUrl"],
                    contentRating=index_pb2.ContentRating.CONTENT_RATING_PORNOGRAPHIC if extension["nsfw"] == 1 else index_pb2.CONTENT_RATING_SAFE,
                )
                for source in extension["sources"]
            ]
        )
        for extension in legacy_index
    ]
)

with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as index_file:
    index_file.write(json_format.MessageToJson(index, always_print_fields_with_no_presence=False, preserving_proto_field_name=True))

with REMOTE_REPO.joinpath("index.pb").open("wb") as index_pb_file:
    index_pb_file.write(index.SerializeToString())

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as index_min_file:
    json.dump(legacy_index, index_min_file, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
    for entry in legacy_index:
        apk_escaped = 'apk/' + html.escape(entry["apk"])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write('</pre>\n</body>\n</html>\n')
