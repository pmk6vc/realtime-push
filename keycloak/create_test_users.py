"""
Necessary because seeding users directly from realm-export.json is not working yet
See https://github.com/keycloak/keycloak/issues/43195

Once Keycloak supports user seeding from realm-export.json, this script can be removed.
"""

#!/usr/bin/env python3
import argparse
import json
import sys
from typing import Any, Dict, List, Optional, Tuple

import requests


def die(msg: str, code: int = 1) -> None:
    print(f"ERROR: {msg}", file=sys.stderr)
    raise SystemExit(code)


def req_json(method: str, url: str, headers: Dict[str, str], **kwargs):
    r = requests.request(method, url, headers=headers, timeout=30, **kwargs)
    if r.status_code >= 400:
        body = r.text.strip()
        die(f"{method} {url} -> {r.status_code}\n{body}")
    if r.status_code == 204 or not r.content:
        return None
    return r.json()


def get_admin_token(base_url: str, admin_realm: str, client_id: str, username: str, password: str) -> str:
    token_url = f"{base_url}/realms/{admin_realm}/protocol/openid-connect/token"
    data = {
        "grant_type": "password",
        "client_id": client_id,
        "username": username,
        "password": password,
    }
    r = requests.post(token_url, data=data, timeout=30)
    if r.status_code >= 400:
        die(f"Token request failed: POST {token_url} -> {r.status_code}\n{r.text.strip()}")
    tok = r.json().get("access_token")
    if not tok:
        die("No access_token returned from Keycloak token endpoint.")
    return tok


def find_user_id(base_url: str, realm: str, headers: Dict[str, str], username: str) -> Optional[str]:
    # Search by username (exact match is not guaranteed by API, so we filter)
    url = f"{base_url}/admin/realms/{realm}/users"
    users = req_json("GET", url, headers, params={"username": username})
    if not users:
        return None
    for u in users:
        if (u.get("username") or "").lower() == username.lower():
            return u.get("id")
    return None


def create_user(base_url: str, realm: str, headers: Dict[str, str], user_repr: Dict[str, Any]) -> str:
    url = f"{base_url}/admin/realms/{realm}/users"
    r = requests.post(url, headers=headers, json=user_repr, timeout=30)
    if r.status_code == 409:
        die(f"User already exists unexpectedly while creating: {user_repr.get('username')}")
    if r.status_code >= 400:
        die(f"Create user failed: POST {url} -> {r.status_code}\n{r.text.strip()}")
    # Keycloak returns Location header with new user id
    loc = r.headers.get("Location", "")
    if not loc or "/users/" not in loc:
        # fallback: search
        uid = find_user_id(base_url, realm, headers, user_repr["username"])
        if not uid:
            die(f"Created user but couldn't determine id for {user_repr['username']}")
        return uid
    return loc.rsplit("/users/", 1)[-1]


def update_user(base_url: str, realm: str, headers: Dict[str, str], user_id: str, user_repr: Dict[str, Any]) -> None:
    url = f"{base_url}/admin/realms/{realm}/users/{user_id}"
    req_json("PUT", url, headers, json=user_repr)


def set_password(base_url: str, realm: str, headers: Dict[str, str], user_id: str, value: str, temporary: bool) -> None:
    url = f"{base_url}/admin/realms/{realm}/users/{user_id}/reset-password"
    payload = {"type": "password", "value": value, "temporary": bool(temporary)}
    req_json("PUT", url, headers, json=payload)


def list_realm_roles(base_url: str, realm: str, headers: Dict[str, str]) -> Dict[str, Dict[str, Any]]:
    url = f"{base_url}/admin/realms/{realm}/roles"
    roles = req_json("GET", url, headers) or []
    return {r["name"]: r for r in roles if "name" in r}


def get_group_by_path(base_url: str, realm: str, headers: Dict[str, str], path: str) -> Optional[Dict[str, Any]]:
    # Keycloak has /group-by-path in newer versions; we try it first, then fallback to tree search.
    path = path if path.startswith("/") else f"/{path}"
    url = f"{base_url}/admin/realms/{realm}/group-by-path/{path.lstrip('/')}"
    r = requests.get(url, headers=headers, timeout=30)
    if r.status_code == 200:
        return r.json()
    if r.status_code not in (404, 405):
        die(f"GET {url} -> {r.status_code}\n{r.text.strip()}")

    # Fallback: brute-force search in group tree
    root_url = f"{base_url}/admin/realms/{realm}/groups"
    groups = req_json("GET", root_url, headers, params={"briefRepresentation": "false"}) or []

    def walk(gs: List[Dict[str, Any]], prefix: str = "") -> Optional[Dict[str, Any]]:
        for g in gs:
            name = g.get("name", "")
            cur_path = f"{prefix}/{name}" if prefix else f"/{name}"
            if cur_path == path:
                return g
            sub = g.get("subGroups") or []
            found = walk(sub, cur_path)
            if found:
                return found
        return None

    return walk(groups)


def add_user_to_group(base_url: str, realm: str, headers: Dict[str, str], user_id: str, group_id: str) -> None:
    url = f"{base_url}/admin/realms/{realm}/users/{user_id}/groups/{group_id}"
    # PUT with empty body
    r = requests.put(url, headers=headers, timeout=30)
    if r.status_code >= 400:
        die(f"Add to group failed: PUT {url} -> {r.status_code}\n{r.text.strip()}")


def get_user_realm_role_mappings(base_url: str, realm: str, headers: Dict[str, str], user_id: str) -> Dict[str, Dict[str, Any]]:
    url = f"{base_url}/admin/realms/{realm}/users/{user_id}/role-mappings/realm"
    roles = req_json("GET", url, headers) or []
    return {r["name"]: r for r in roles if "name" in r}


def add_realm_roles_to_user(base_url: str, realm: str, headers: Dict[str, str], user_id: str, roles_to_add: List[Dict[str, Any]]) -> None:
    url = f"{base_url}/admin/realms/{realm}/users/{user_id}/role-mappings/realm"
    req_json("POST", url, headers, json=roles_to_add)


def normalize_user_for_put(u: Dict[str, Any]) -> Dict[str, Any]:
    # Only include common, safe fields for create/update.
    allowed = {
        "username",
        "email",
        "firstName",
        "lastName",
        "enabled",
        "emailVerified",
        "attributes",
    }
    out = {k: u[k] for k in allowed if k in u}
    # Keycloak rejects nulls sometimes; drop them.
    return {k: v for k, v in out.items() if v is not None}


def seed(base_url: str, admin_realm: str, admin_client_id: str, admin_user: str, admin_pass: str, data: Dict[str, Any], dry_run: bool) -> None:
    realm = data.get("realm")
    if not realm:
        die("JSON must include top-level field: realm")

    users = data.get("users") or []
    if not isinstance(users, list):
        die("JSON field 'users' must be a list")

    token = get_admin_token(base_url, admin_realm, admin_client_id, admin_user, admin_pass)
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}

    realm_roles = list_realm_roles(base_url, realm, headers)

    for u in users:
        username = u.get("username")
        if not username:
            die("Each user must have a username")

        user_repr = normalize_user_for_put(u)
        user_id = find_user_id(base_url, realm, headers, username)

        if user_id:
            print(f"[user] {username}: exists -> updating basic fields")
            if not dry_run:
                update_user(base_url, realm, headers, user_id, user_repr)
        else:
            print(f"[user] {username}: creating")
            if not dry_run:
                user_id = create_user(base_url, realm, headers, user_repr)
            else:
                user_id = "DRY_RUN_USER_ID"

        # Password
        pw = u.get("password")
        if pw and isinstance(pw, dict) and pw.get("value"):
            print(f"  - set password (temporary={bool(pw.get('temporary', False))})")
            if not dry_run:
                set_password(base_url, realm, headers, user_id, pw["value"], bool(pw.get("temporary", False)))

        # Groups
        groups = u.get("groups") or []
        for gpath in groups:
            if not isinstance(gpath, str):
                die(f"groups entries must be strings (got {gpath})")
            print(f"  - add to group {gpath}")
            if not dry_run:
                g = get_group_by_path(base_url, realm, headers, gpath)
                if not g:
                    die(f"Group not found by path: {gpath}")
                add_user_to_group(base_url, realm, headers, user_id, g["id"])

        # Realm roles
        desired_roles = u.get("realmRoles") or []
        if desired_roles:
            if not dry_run:
                current = get_user_realm_role_mappings(base_url, realm, headers, user_id)
            else:
                current = {}

            to_add = []
            for rname in desired_roles:
                if rname in current:
                    continue
                role = realm_roles.get(rname)
                if not role:
                    die(f"Realm role '{rname}' not found in realm '{realm}'")
                # Admin API expects objects with at least id and name
                to_add.append({"id": role["id"], "name": role["name"]})

            if to_add:
                print(f"  - add realm roles: {', '.join([r['name'] for r in to_add])}")
                if not dry_run:
                    add_realm_roles_to_user(base_url, realm, headers, user_id, to_add)
            else:
                print("  - realm roles already satisfied")

    print("Done.")


def main():
    ap = argparse.ArgumentParser(description="Seed Keycloak users from JSON")
    ap.add_argument("--keycloak-url", required=True, help="Base Keycloak URL, e.g. http://localhost:8080")
    ap.add_argument("--admin-realm", default="master", help="Admin realm (usually master)")
    ap.add_argument("--admin-client-id", default="admin-cli", help="Admin client id (usually admin-cli)")
    ap.add_argument("--admin-user", required=True, help="Admin username")
    ap.add_argument("--admin-pass", required=True, help="Admin password")
    ap.add_argument("--file", required=True, help="Path to seed JSON file")
    ap.add_argument("--dry-run", action="store_true", help="Print actions without mutating Keycloak")
    args = ap.parse_args()

    try:
        with open(args.file, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        die(f"Failed to read JSON file: {e}")

    seed(
        base_url=args.keycloak_url.rstrip("/"),
        admin_realm=args.admin_realm,
        admin_client_id=args.admin_client_id,
        admin_user=args.admin_user,
        admin_pass=args.admin_pass,
        data=data,
        dry_run=args.dry_run,
    )


if __name__ == "__main__":
    main()
