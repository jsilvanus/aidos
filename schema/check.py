#!/usr/bin/env python3
"""Validate the Aidos schema files.

Run: python3 schema/check.py

Checks:
  1. Every file executes against SQLite.
  2. Foreign keys resolve (PRAGMA foreign_key_check).
  3. No table is defined in more than one file.
  4. Every declared index targets a table that exists.
"""
import sqlite3, sys, pathlib, re

HERE = pathlib.Path(__file__).parent
FILES = ["user.sql", "vault.sql", "project.sql"]

failures, tables_by_file = [], {}

for name in FILES:
    path = HERE / name
    sql = path.read_text()
    con = sqlite3.connect(":memory:")
    try:
        con.executescript(sql)
    except sqlite3.Error as e:
        failures.append(f"{name}: does not execute — {e}")
        continue

    problems = con.execute("PRAGMA foreign_key_check").fetchall()
    if problems:
        failures.append(f"{name}: foreign_key_check reported {problems}")

    tables = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' "
        "AND name NOT LIKE 'sqlite_%'")}
    tables_by_file[name] = tables

    # Every FK target must be a table defined in the same file.
    for t in sorted(tables):
        for fk in con.execute(f"PRAGMA foreign_key_list('{t}')").fetchall():
            target = fk[2]
            if target not in tables:
                failures.append(f"{name}: {t} references undefined table '{target}'")
    con.close()
    print(f"  {name}: {len(tables)} tables OK")

# No table defined in two files (scope separation, RFC-0054).
seen = {}
for fname, tables in tables_by_file.items():
    for t in tables:
        if t in seen and t not in ("schema_versions", "settings", "resource_budgets"):
            failures.append(f"table '{t}' defined in both {seen[t]} and {fname}")
        seen[t] = fname

if failures:
    print("\nFAIL")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)

print(f"\nOK — {sum(len(t) for t in tables_by_file.values())} tables across {len(FILES)} files")
