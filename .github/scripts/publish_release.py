#!/usr/bin/env python3
"""Publish the built artifacts to the public download repo.

This is the last step of the build, and the only one that needs a tool the machine has to have
installed — so it uses none. `gh` was doing this, which meant a runner without the GitHub CLI could
build a release perfectly and then fail on the upload; a self-hosted Mac is exactly such a machine.
Python and its standard library are on every runner that can already run this workflow.

What it does is what `gh release create` did:

  a v* tag   one release, kept forever, carrying every file in the given directory. An asset that
             is already there is replaced, so a re-run of a failed publish finishes the job.
  main       one rolling `nightly` prerelease — deleted, tag and all, and remade, so yesterday's
             assets are never left beside today's.

Environment: TARGET_REPO, FLOW_RELEASE_TOKEN, GITHUB_REF, GITHUB_SHA, GITHUB_REPOSITORY.
`--dry-run` prints what it would do and touches nothing, which is how this is tested.
"""

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def request(method, url, token, data=None, content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as response:
            body = response.read()
            return response.status, (json.loads(body) if body else {})
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body) if body else {}
        except json.JSONDecodeError:
            return e.code, {"message": body.decode("utf-8", "replace")[:300]}


def release_by_tag(repo, tag, token):
    status, body = request("GET", f"{API}/repos/{repo}/releases/tags/{tag}", token)
    return body if status == 200 else None


def delete_nightly(repo, token, dry_run):
    """The rolling build is remade rather than added to: its tag moves and its assets go stale."""
    existing = release_by_tag(repo, "nightly", token)
    if existing:
        print(f"deleting the previous nightly (release {existing['id']})")
        if not dry_run:
            request("DELETE", f"{API}/repos/{repo}/releases/{existing['id']}", token)
    if not dry_run:
        # the tag outlives the release it was made for, and a new one cannot reuse it
        request("DELETE", f"{API}/repos/{repo}/git/refs/tags/nightly", token)


def create_release(repo, token, dry_run, **fields):
    print(f"creating release {fields['tag_name']}")
    if dry_run:
        return {"id": 0}
    status, body = request("POST", f"{API}/repos/{repo}/releases", token, json.dumps(fields).encode())
    if status == 201:
        return body
    # 422 is "already exists": a re-run after a publish that failed partway through
    if status == 422:
        existing = release_by_tag(repo, fields["tag_name"], token)
        if existing:
            print("  it already exists — uploading into it")
            return existing
    sys.exit(f"could not create the release: {status} {body.get('message')}")


def upload(repo, release, path, token, dry_run):
    name = os.path.basename(path)
    size = os.path.getsize(path)
    for asset in release.get("assets", []):
        if asset["name"] == name:
            print(f"  replacing {name}")
            if not dry_run:
                request("DELETE", f"{API}/repos/{repo}/releases/assets/{asset['id']}", token)
            break
    print(f"  {name} ({size // 1024} KiB)")
    if dry_run:
        return True
    with open(path, "rb") as f:
        status, body = request(
            "POST",
            f"{UPLOADS}/repos/{repo}/releases/{release['id']}/assets?name={name}",
            token,
            f.read(),
            content_type="application/octet-stream",
        )
    if status != 201:
        print(f"  FAILED {name}: {status} {body.get('message')}", file=sys.stderr)
        return False
    return True


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry_run = "--dry-run" in sys.argv
    directory = args[0] if args else "dist"

    repo = os.environ.get("TARGET_REPO", "siprikorea/flow")
    token = os.environ.get("FLOW_RELEASE_TOKEN", "")
    ref = os.environ.get("GITHUB_REF", "")
    sha = os.environ.get("GITHUB_SHA", "")[:7]
    source = os.environ.get("GITHUB_REPOSITORY", "siprikorea/dataflow-editor")
    if not token and not dry_run:
        sys.exit("FLOW_RELEASE_TOKEN is not set — a repo's own token cannot write to another repo")

    files = sorted(
        os.path.join(directory, f) for f in os.listdir(directory)
        if os.path.isfile(os.path.join(directory, f))
    )
    if not files:
        sys.exit(f"{directory} is empty — there is nothing to publish")

    if ref.startswith("refs/tags/"):
        tag = ref[len("refs/tags/"):]
        release = create_release(
            repo, token, dry_run,
            tag_name=tag, name=f"Flow {tag}", body=f"Built from {source}@{sha}.",
        )
    else:
        delete_nightly(repo, token, dry_run)
        release = create_release(
            repo, token, dry_run,
            tag_name="nightly", target_commitish="main", prerelease=True,
            name="Flow (latest build of main)",
            body=(
                f"Latest build of main — {source}@{sha}. "
                "Unreleased; use the newest v* release for a stable build."
            ),
        )

    failed = [p for p in files if not upload(repo, release, p, token, dry_run)]
    if failed:
        sys.exit(f"{len(failed)} of {len(files)} assets did not upload")
    print(f"published {len(files)} assets to {repo}")


if __name__ == "__main__":
    main()
