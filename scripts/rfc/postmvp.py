#!/usr/bin/env python3
"""Validate and summarise the `## Post-MVP Work` checklists in docs/rfcs.

Each RFC with remaining post-MVP work carries a fenced yaml block under `## Post-MVP Work`:

    post_mvp:
      - id: "0033.1"        # <rfc>.<n>, stable, never reused
        title: "..."
        status: todo        # todo | partial | done
        phase: P1           # optional label for items that chain
        depends_on: []      # optional list of ids
        done_when: "..."    # optional one-line condition (also the semantic-search query)
        files: []           # code that implements it (required, and must exist, when done/partial)

It also checks every RFC's `## Implementation Files` yaml block: the block must exist and every
path in it must be repo-root-relative and present in the tree.

Usage: postmvp.py [--report] [--json]. Exits 1 on validation errors.
Stdlib only: the block is a deliberately small yaml subset.
"""
import glob, json, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))

def unq(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in '"\'':
        return s[1:-1].replace('\\"', '"')
    return s

def parse_block(text):
    items, cur, in_files = [], None, False
    for raw in text.split('\n'):
        if not raw.strip() or raw.strip().startswith('#') or raw.strip() == 'post_mvp:':
            continue
        m = re.match(r'^\s{2}- id:\s*(.*)$', raw)
        if m:
            cur = {'id': unq(m.group(1)), 'files': [], 'depends_on': []}
            items.append(cur); in_files = None; continue
        m = re.match(r'^\s{4}(\w+):\s*(.*)$', raw)
        if m and cur is not None:
            k, v = m.group(1), m.group(2)
            if k in ('files', 'depends_on'):
                in_files = k
                if v.strip().startswith('['):
                    cur[k] = [unq(x) for x in v.strip()[1:-1].split(',') if x.strip()]
                    in_files = None
            else:
                cur[k] = unq(v); in_files = None
            continue
        m = re.match(r'^\s{6}-\s*(.*)$', raw)
        if m and cur is not None and in_files:
            cur[in_files].append(unq(m.group(1))); continue
        raise ValueError('unparseable line: %r' % raw)
    return items

def load():
    out = {}
    for f in sorted(glob.glob(os.path.join(ROOT, 'docs/rfcs/0*.md'))):
        t = open(f).read()
        m = re.search(r'^## Post-MVP Work\s*\n.*?```yaml\n(.*?)```', t, re.S | re.M)
        out[os.path.basename(f)] = (t, parse_block(m.group(1)) if m else None)
    return out

def impl_files(text):
    """Paths in an RFC's `## Implementation Files` yaml block (CLAUDE.md, "Implementation file
    tracking"), or None when the RFC has no such block."""
    sec = re.search(r'^## Implementation Files\s*\n(.*?)(?=^## |\Z)', text, re.S | re.M)
    blk = re.search(r'```yaml\n(.*?)```', sec.group(1), re.S) if sec else None
    if not blk:
        return None
    paths = []
    for raw in blk.group(1).split('\n'):
        line = raw.split(' #')[0].strip()
        if not line or line.startswith('#') or line in ('files:', 'files: []'):
            continue
        m = re.match(r'^-\s*(.+)$', line)
        if not m:
            raise ValueError('unparseable Implementation Files line: %r' % raw)
        paths.append(unq(m.group(1)))
    return paths

def main():
    errs, rows = [], []
    # One number names one RFC: two files sharing a number make every bare "RFC-NNNN" ambiguous.
    seen_nums = {}
    for name in load():
        if name[:4] in seen_nums:
            errs.append('%s: RFC number %s is already used by %s' % (name, name[:4], seen_nums[name[:4]]))
        seen_nums[name[:4]] = name
    for name, (t, items) in load().items():
        # A listed implementation file that no longer exists means the RFC points at code that was
        # moved or deleted: the list is what tools and reviewers use to find an RFC's code.
        try:
            listed = impl_files(t)
        except ValueError as e:
            errs.append('%s: %s' % (name, e)); listed = []
        if listed is None:
            errs.append('%s: no ## Implementation Files yaml block' % name)
        for p in listed or []:
            if p.startswith('/') or '..' in p.split('/'):
                errs.append('%s: Implementation Files path must be repo-root-relative: %s' % (name, p))
            elif not os.path.exists(os.path.join(ROOT, p)):
                errs.append('%s: Implementation Files lists a missing file: %s' % (name, p))
        hdr = re.search(r'^Non-MVP Work: \*\*(Remaining|None)\*\*', t, re.M)
        if hdr is None and re.search(r'^\*{0,2}Status:?\*{0,2}:?\s*\*{0,2}Implemented', t, re.M):
            errs.append('%s: Implemented but has no "Non-MVP Work:" header line' % name)
        if items is None:
            if hdr and hdr.group(1) == 'Remaining':
                errs.append('%s: header says Remaining but there is no ## Post-MVP Work block' % name)
            continue
        num = name[:4]
        seen = set()
        for it in items:
            i = it['id']
            if not re.match(r'^%s\.\d+$' % num, i): errs.append('%s: bad id %s' % (name, i))
            if i in seen: errs.append('%s: duplicate id %s' % (name, i))
            seen.add(i)
            if it.get('status') not in ('todo', 'partial', 'done'): errs.append('%s %s: bad status' % (name, i))
            if not it.get('title'): errs.append('%s %s: no title' % (name, i))
            if it.get('status') in ('partial', 'done'):
                if not it['files']: errs.append('%s %s: %s needs files' % (name, i, it['status']))
                for p in it['files']:
                    if not os.path.exists(os.path.join(ROOT, p)): errs.append('%s %s: missing file %s' % (name, i, p))
            for d in it['depends_on']:
                if d not in seen and not any(x['id'] == d for x in items): errs.append('%s %s: unknown dep %s' % (name, i, d))
        done = sum(1 for x in items if x['status'] == 'done')
        part = sum(1 for x in items if x['status'] == 'partial')
        allDone = done == len(items)
        if hdr and (hdr.group(1) == 'None') != allDone:
            errs.append('%s: header %s disagrees with items (%d/%d done)' % (name, hdr.group(1), done, len(items)))
        rows.append((num, len(items), done, part))
    if '--json' in sys.argv or '--report' in sys.argv:
        tot = sum(r[1] for r in rows); d = sum(r[2] for r in rows); p = sum(r[3] for r in rows)
        if '--json' in sys.argv:
            print(json.dumps({'items': tot, 'done': d, 'partial': p, 'rfcs': rows}))
        else:
            for r in rows: print('%s  %2d items  %2d done  %2d partial' % r)
            print('TOTAL %d items, %d done (%.1f%%), %d partial' % (tot, d, 100.0 * d / max(tot, 1), p))
    for e in errs: print('ERROR', e, file=sys.stderr)
    sys.exit(1 if errs else 0)

main()
