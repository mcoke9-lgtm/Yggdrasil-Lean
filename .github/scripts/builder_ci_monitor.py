#!/usr/bin/env python3
"""Read-only GitHub Actions monitor for the Nine Builder runtime branch.

The monitor never reruns, cancels, publishes, or modifies workflows. It reports
required workflow state for the exact current branch HEAD.
"""
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

API = os.environ.get("GITHUB_API_URL", "https://api.github.com")
REPO = os.environ.get("GITHUB_REPOSITORY", "mcoke9-lgtm/Yggdrasil-Lean")
BRANCH = os.environ.get("MONITOR_BRANCH", "independent-builder-runtime-foundation-001")
TOKEN = os.environ.get("GITHUB_TOKEN", "")
REQUIRED = (
    "Lean build and analysis engine checks",
    "Builder Conversation Journal",
    "Independent Builder Runtime Android",
)

def get_json(path):
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Yggdrasil-Nine-Builder-CI-Monitor",
    }
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    request = urllib.request.Request(API + path, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)

def build_report(branch_data, runs_data):
    head = branch_data["commit"]["sha"]
    runs = runs_data.get("workflow_runs", [])
    results = []
    for name in REQUIRED:
        matching = [r for r in runs if r.get("name") == name and r.get("head_sha") == head]
        matching.sort(key=lambda r: (r.get("run_attempt", 1), r.get("id", 0)), reverse=True)
        if not matching:
            results.append({"workflow": name, "status": "not_found", "conclusion": None, "run_id": None})
            continue
        run = matching[0]
        results.append({
            "workflow": name,
            "status": run.get("status", "unknown"),
            "conclusion": run.get("conclusion"),
            "run_id": run.get("id"),
            "url": run.get("html_url"),
            "run_attempt": run.get("run_attempt", 1),
        })
    complete = all(r["status"] == "completed" for r in results)
    success = complete and all(r["conclusion"] == "success" for r in results)
    return {
        "repository": REPO,
        "branch": BRANCH,
        "head_sha": head,
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "overall": "success" if success else ("pending" if not complete else "failure"),
        "workflows": results,
        "policy": "read-only; no reruns, cancellations, deployments, or repository writes",
    }

def render(report):
    lines = [
        "# Nine Builder CI monitor",
        "",
        f"- Branch: `{report['branch']}`",
        f"- Commit: `{report['head_sha']}`",
        f"- Checked: {report['checked_at']}",
        f"- Overall: **{report['overall'].upper()}**",
        "",
        "| Required workflow | Status | Conclusion | Run |",
        "|---|---|---|---|",
    ]
    for item in report["workflows"]:
        run = f"[{item['run_id']}]({item['url']})" if item.get("run_id") and item.get("url") else "—"
        lines.append(f"| {item['workflow']} | {item['status']} | {item.get('conclusion') or '—'} | {run} |")
    lines += ["", "Read-only monitor. A missing run is not interpreted as success."]
    return "\n".join(lines) + "\n"

def main():
    branch = get_json(f"/repos/{REPO}/branches/{BRANCH}")
    runs = get_json(f"/repos/{REPO}/actions/runs?branch={BRANCH}&per_page=100")
    report = build_report(branch, runs)
    os.makedirs("monitor-output", exist_ok=True)
    with open("monitor-output/ci-status.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    markdown = render(report)
    with open("monitor-output/ci-status.md", "w", encoding="utf-8") as f:
        f.write(markdown)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(markdown)
    print(markdown)
    if report["overall"] == "failure":
        sys.exit(1)

if __name__ == "__main__":
    main()
