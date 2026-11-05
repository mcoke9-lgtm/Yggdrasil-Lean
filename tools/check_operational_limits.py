#!/usr/bin/env python3
"""Read-only pre-action gate for the Yggdrasil operational limits register.

Exit codes: 0=proceed/conditioned, 2=blocked, 3=recheck required, 4=authorization required.
This tool does not refresh telemetry, mutate the register, or perform the requested action.
"""
import argparse
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path


def evaluate(register, capability, action=None, as_of=None, max_age_days=7):
    today = as_of or date.today()
    target = capability.strip().casefold()
    matches = [
        item for item in register.get("constraints", [])
        if str(item.get("capability", "")).strip().casefold() == target
    ]
    if not matches:
        return {"decision": "recheck_required", "reason": "No exact capability record exists; availability is unknown.",
                "capability": capability, "evidence_source": None, "safe_alternative": None}, 3
    if len(matches) != 1:
        return {"decision": "recheck_required", "reason": "Multiple exact capability records exist; reconcile them before acting.",
                "capability": capability, "evidence_source": None, "safe_alternative": None}, 3

    item = matches[0]
    result = {
        "capability": item.get("capability"),
        "scope": item.get("scope"),
        "recorded_state": item.get("state", "unknown"),
        "evidence_source": item.get("evidence_source"),
        "observed_at": item.get("observed_at"),
        "remaining_capacity": item.get("remaining_capacity"),
        "unit": item.get("unit"),
        "safe_alternative": item.get("safe_alternative"),
    }

    if action:
        forbidden = item.get("actions_not_authorized", [])
        if any(str(rule).strip().casefold() in action.strip().casefold() for rule in forbidden):
            result.update(decision="authorization_required",
                          reason="The requested action is explicitly marked as not authorized.")
            return result, 4

    observed = item.get("observed_at")
    try:
        observed_date = date.fromisoformat(str(observed)[:10])
        age = (today - observed_date).days
        if age < 0:
            raise ValueError("observation date is in the future")
    except (TypeError, ValueError):
        result.update(decision="recheck_required", reason="Observation date is missing or invalid.")
        return result, 3

    result["evidence_age_days"] = age
    if age > max_age_days:
        result.update(decision="recheck_required",
                      reason=f"Evidence is {age} days old (freshness limit: {max_age_days}); refresh before acting.")
        return result, 3

    maximum = item.get("maximum_capacity")
    usage = item.get("current_usage")
    remaining = item.get("remaining_capacity")
    if all(isinstance(value, (int, float)) and not isinstance(value, bool)
           for value in (maximum, usage, remaining)):
        if maximum < 0 or usage < 0 or remaining < 0 or usage > maximum or remaining != maximum - usage:
            result.update(decision="recheck_required",
                          reason="Capacity figures are inconsistent; reconcile maximum, usage, and remaining capacity.")
            return result, 3

    state = str(item.get("state", "unknown")).casefold()
    if state == "blocked":
        result.update(decision="blocked", reason="Fresh evidence records this capability as blocked.")
        return result, 2
    if state in ("unknown", "stale"):
        result.update(decision="recheck_required", reason=f"Register state is {state}; verify current availability.")
        return result, 3
    if state == "constrained":
        result.update(decision="proceed_with_constraints",
                      reason="Capability is constrained; honor the recorded limits and safe alternative.")
        return result, 0
    if state == "available":
        result.update(decision="proceed", reason="Fresh register evidence records this capability as available.")
        return result, 0
    result.update(decision="recheck_required", reason=f"Unrecognized register state: {state}.")
    return result, 3


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--register", default="app-registry/operational-limits.json")
    parser.add_argument("--capability", required=True, help="Exact capability name from the register")
    parser.add_argument("--action", help="Requested action, checked against explicit authorization exclusions")
    parser.add_argument("--max-age-days", type=int, default=7)
    parser.add_argument("--as-of", help="ISO date override for deterministic tests")
    args = parser.parse_args()
    if args.max_age_days < 0:
        parser.error("--max-age-days must be zero or greater")
    try:
        register = json.loads(Path(args.register).read_text(encoding="utf-8"))
        as_of = date.fromisoformat(args.as_of) if args.as_of else date.today()
        result, code = evaluate(register, args.capability, args.action, as_of, args.max_age_days)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        result, code = {"decision": "recheck_required", "reason": f"Register could not be read or parsed: {exc}",
                        "capability": args.capability}, 3
    print(json.dumps(result, indent=2, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
