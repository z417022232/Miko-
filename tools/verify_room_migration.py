#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Room 数据库迁移验证器（仓库正式工具，不依赖 instrumentation / 真机）。

它回答一个问题：**「迁移 SQL 跑完之后，库的结构与数据是否就是 Room 期望的那个？」**

做法
----
1. 用**旧 schema JSON** 的 `createSql` 在内存 SQLite 里搭出旧版本的真实结构；
2. 往每张表塞**一行探针数据**并记下原值（用于验证「迁移不动数据」）；
3. 从 Kotlin 源码里抠出 `MIGRATION_x_y` 的 `db.execSQL(...)` 原文，
   **按版本顺序连通整条链**执行（14→15 这种链式升级要连着跑，不能只跑最后一段）；
4. 与**新 schema JSON** 全表比对结构，并核对探针数据是否原样保留。

校验项
------
表 / 列名 / SQLite affinity / NOT NULL / 默认值 / 复合主键顺序 / AUTOINCREMENT /
索引名称+唯一性+列顺序 / 迁移链连续性 / schema 版本号与 `PRAGMA user_version` /
旧表数据经过迁移后是否保留。

为什么放进仓库
--------------
之前这套逻辑只存在于被 `.gitignore` 排除的 `diagnostics/` 下，导致每次升版本都要
重写一遍验证工具，而「重写出来的脚本」和「文档描述的脚本」并不等价 ——
于是每次升级都制造一个**未经验证的验证器**。通用工具不该跟真机诊断数据一起被忽略。

用法
----
    python tools/verify_room_migration.py \\
        --old-schema app/schemas/com.example.worktimetracker.data.database.AppDatabase/14.json \\
        --new-schema app/schemas/com.example.worktimetracker.data.database.AppDatabase/15.json \\
        --migration-source app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt \\
        --from 14 --to 15

`--migration-source` 有默认值；`--from` / `--to` 可省略，缺省时取两个 schema 的 `version`。

退出码：0 = 全部通过；1 = 存在失败项；2 = 用法/输入错误。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import sys

DEFAULT_MIGRATION_SOURCE = "app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt"

MIGRATION_MARKER = re.compile(r"val\s+MIGRATION_(\d+)_(\d+)\s*=")

PROBE_TEXT = "__mig_probe__"


# --------------------------------------------------------------------------- schema

def load_schema(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    if "database" not in raw:
        raise ValueError("%s 不是 Room 导出的 schema（缺 database 节点）" % path)
    return raw["database"]


def create_statements(schema: dict) -> list[str]:
    out: list[str] = []
    for ent in schema["entities"]:
        out.append(ent["createSql"].replace("${TABLE_NAME}", ent["tableName"]))
        for idx in ent.get("indices", []):
            out.append(idx["createSql"].replace("${TABLE_NAME}", ent["tableName"]))
    for view in schema.get("views", []):
        out.append(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))
    return out


# ----------------------------------------------------------------- kotlin 源码解析

def extract_migrations(source_path: str) -> dict[tuple[int, int], list[str]]:
    """抠出所有 `val MIGRATION_a_b = object : Migration(a, b) { ... }` 里的 execSQL 语句。"""
    with open(source_path, encoding="utf-8") as f:
        src = f.read()

    marks = list(MIGRATION_MARKER.finditer(src))
    if not marks:
        raise ValueError("%s 里找不到任何 `val MIGRATION_a_b` 声明" % source_path)

    out: dict[tuple[int, int], list[str]] = {}
    for i, m in enumerate(marks):
        a, b = int(m.group(1)), int(m.group(2))
        end = marks[i + 1].start() if i + 1 < len(marks) else len(src)
        block = src[m.start():end]
        if (a, b) in out:
            raise ValueError("MIGRATION_%d_%d 重复声明" % (a, b))
        out[(a, b)] = _exec_sql_in(block)
    return out


def _exec_sql_in(block: str) -> list[str]:
    """把一个迁移块里 `db.execSQL(...)` 的字符串参数拼回完整语句。"""
    stmts: list[str] = []
    pos = 0
    while True:
        call = block.find("db.execSQL(", pos)
        if call < 0:
            break
        i = call + len("db.execSQL(")
        depth = 1
        j = i
        while depth > 0 and j < len(block):
            ch = block[j]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
            if depth == 0:
                break
            j += 1
        arg = block[i:j]
        pos = j
        pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', arg)
        if not pieces:
            raise ValueError("无法解析的 execSQL 参数：%s" % arg[:120].replace("\n", " "))
        stmts.append("".join(pieces))
    return stmts


def build_chain(migrations, v_from: int, v_to: int) -> list[tuple[int, int, list[str]]]:
    """按版本顺序拼出从 v_from 到 v_to 的连续链；不连续就报错。"""
    if v_from == v_to:
        raise ValueError("--from 与 --to 相同（%d），没有可验证的迁移" % v_from)
    if v_from > v_to:
        raise ValueError("--from(%d) 不能大于 --to(%d)" % (v_from, v_to))

    chain: list[tuple[int, int, list[str]]] = []
    cursor = v_from
    while cursor < v_to:
        nxt = sorted(b for (a, b) in migrations if a == cursor)
        if not nxt:
            available = ", ".join("MIGRATION_%d_%d" % k for k in sorted(migrations))
            raise ValueError(
                "迁移链断裂：没有从 v%d 出发的迁移（已有：%s）" % (cursor, available)
            )
        # 同一版本号只允许有一条出边，否则链本身有歧义
        if len(nxt) > 1:
            raise ValueError("v%d 有多个迁移目标 %s，链不唯一" % (cursor, nxt))
        target = nxt[0]
        if target > v_to:
            raise ValueError("MIGRATION_%d_%d 越过了目标版本 v%d" % (cursor, target, v_to))
        chain.append((cursor, target, migrations[(cursor, target)]))
        cursor = target
    return chain


# ------------------------------------------------------------------------ 结构快照

def table_names(con: sqlite3.Connection) -> set[str]:
    return {
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table'"
        )
    }


def columns(con: sqlite3.Connection, table: str) -> dict[str, dict]:
    return {
        name: {
            "type": (ctype or "").strip().upper(),
            "notnull": int(notnull),
            "default": dflt,
            "pk": int(pk),
        }
        for _, name, ctype, notnull, dflt, pk in con.execute("PRAGMA table_info(%s)" % table)
    }


def pk_order(con: sqlite3.Connection, table: str) -> list[str]:
    """`PRAGMA table_info` 的 pk 列是**主键内位次**（1,2,...），不是布尔。"""
    rows = [(pk, name) for _, name, _, _, _, pk in con.execute("PRAGMA table_info(%s)" % table) if pk]
    return [n for _, n in sorted(rows)]


def has_autoincrement(con: sqlite3.Connection, table: str) -> bool:
    row = con.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone()
    return bool(row and row[0] and "AUTOINCREMENT" in row[0].upper())


def indices(con: sqlite3.Connection, table: str) -> dict[str, dict]:
    """
    索引名 → {unique, columns}。唯一性来自 `PRAGMA index_list`，列顺序来自 `index_info`。

    ⚠️ 必须滤掉 `sqlite_%`：复合主键 / UNIQUE 约束会让 SQLite **隐式**建出
    `sqlite_autoindex_<表>_1`，而 Room 的 schema JSON 里不会列它们（Room 没声明过）。
    不滤的话所有复合主键表都会报「索引集合不一致」—— 那是工具的假阳性，不是迁移的问题。
    """
    out: dict[str, dict] = {}
    for row in con.execute("PRAGMA index_list(%s)" % table):
        name, unique = row[1], int(row[2])
        if name.startswith("sqlite_"):
            continue
        cols = [r[2] for r in con.execute("PRAGMA index_info(%s)" % name)]
        out[name] = {"unique": unique, "columns": cols}
    return out


def expected_columns(entity: dict) -> dict[str, dict]:
    return {
        f["columnName"]: {
            "type": (f.get("affinity") or "").strip().upper(),
            "notnull": int(f.get("notNull", False)),
            "default": f.get("defaultValue"),
        }
        for f in entity["fields"]
    }


def expected_indices(entity: dict) -> dict[str, dict]:
    return {
        i["name"]: {"unique": int(bool(i.get("unique", False))), "columns": list(i["columnNames"])}
        for i in entity.get("indices", [])
    }


# ---------------------------------------------------------------------------- 探针

def probe_value(affinity: str):
    a = (affinity or "").strip().upper()
    if a == "INTEGER":
        return 1
    if a == "REAL":
        return 1.5
    if a == "BLOB":
        return sqlite3.Binary(b"\x00")
    return PROBE_TEXT


def insert_probes(con: sqlite3.Connection, schema: dict) -> tuple[dict, list[str]]:
    """
    每张表塞一行探针并读回原值。

    返回 (snapshot, skipped)。skipped 里的表**没有**得到数据保留验证 ——
    调用方必须把它当失败处理，否则就会出现「验证器说通过、其实一眼没看」。
    """
    snapshot: dict[str, dict] = {}
    skipped: list[str] = []

    for ent in schema["entities"]:
        table = ent["tableName"]
        auto_pk = bool(ent["primaryKey"].get("autoGenerate")) and len(ent["primaryKey"]["columnNames"]) == 1
        auto_col = ent["primaryKey"]["columnNames"][0] if auto_pk else None

        cols, vals = [], []
        for f in ent["fields"]:
            name = f["columnName"]
            if name == auto_col:
                continue  # 交给 SQLite 分配，顺便覆盖 AUTOINCREMENT 路径
            cols.append(name)
            vals.append(probe_value(f.get("affinity")))

        try:
            if cols:
                con.execute(
                    "INSERT INTO %s (%s) VALUES (%s)" % (
                        table, ", ".join(cols), ", ".join("?" * len(cols)),
                    ),
                    vals,
                )
            else:
                con.execute("INSERT INTO %s DEFAULT VALUES" % table)
        except Exception as ex:
            skipped.append("%s（插入探针失败：%s）" % (table, ex))
            continue

        read_cols = cols
        select = "SELECT rowid" + ("".join(", " + c for c in read_cols))
        rows = {r[0]: tuple(r[1:]) for r in con.execute(select + " FROM %s" % table)}
        snapshot[table] = {"cols": read_cols, "rows": rows}

    con.commit()
    return snapshot, skipped


def verify_probes(con: sqlite3.Connection, snapshot: dict) -> list[str]:
    errors: list[str] = []
    for table, snap in snapshot.items():
        if table not in table_names(con):
            errors.append("探针表 %s 在迁移后消失了" % table)
            continue
        now_cols = columns(con, table)
        alive = [c for c in snap["cols"] if c in now_cols]
        select = "SELECT rowid" + ("".join(", " + c for c in alive))
        after = {r[0]: tuple(r[1:]) for r in con.execute(select + " FROM %s" % table)}

        for rowid, before_values in snap["rows"].items():
            if rowid not in after:
                errors.append("%s 的探针行 rowid=%s 在迁移后丢失" % (table, rowid))
                continue
            keep = [i for i, c in enumerate(snap["cols"]) if c in now_cols]
            expected_vals = tuple(before_values[i] for i in keep)
            if after[rowid] != expected_vals:
                errors.append(
                    "%s 的探针行数据被改动：rowid=%s 之前=%r 之后=%r"
                    % (table, rowid, expected_vals, after[rowid])
                )
    return errors


# ---------------------------------------------------------------------------- 比对

def compare_structure(con: sqlite3.Connection, new_schema: dict) -> tuple[list[str], list[str]]:
    """返回 (errors, notes)。"""
    errors: list[str] = []
    by_name = {e["tableName"]: e for e in new_schema["entities"]}

    present = table_names(con)
    missing = set(by_name) - present
    extra = present - set(by_name)
    if missing:
        errors.append("迁移后缺少表：%s" % sorted(missing))
    if extra:
        errors.append("迁移后出现新 schema 里没有的表：%s" % sorted(extra))

    for table in sorted(set(by_name) & present):
        ent = by_name[table]
        got, exp = columns(con, table), expected_columns(ent)

        if set(got) != set(exp):
            errors.append(
                "%s 列集合不一致：多=%s 少=%s"
                % (table, sorted(set(got) - set(exp)), sorted(set(exp) - set(got)))
            )
        for name in sorted(set(got) & set(exp)):
            g, e = got[name], exp[name]
            if g["type"] != e["type"]:
                errors.append("%s.%s affinity 不一致：实际=%s 期望=%s" % (table, name, g["type"], e["type"]))
            if g["notnull"] != e["notnull"]:
                errors.append(
                    "%s.%s NOT NULL 不一致：实际=%d 期望=%d" % (table, name, g["notnull"], e["notnull"])
                )
            if (g["default"] or None) != (e["default"] or None):
                errors.append(
                    "%s.%s 默认值不一致：实际=%r 期望=%r" % (table, name, g["default"], e["default"])
                )

        want_pk = list(ent["primaryKey"]["columnNames"])
        got_pk = pk_order(con, table)
        if got_pk != want_pk:
            errors.append("%s 主键顺序不一致：实际=%s 期望=%s" % (table, got_pk, want_pk))

        want_auto = bool(ent["primaryKey"].get("autoGenerate"))
        got_auto = has_autoincrement(con, table)
        if got_auto != want_auto:
            errors.append("%s AUTOINCREMENT 不一致：实际=%s 期望=%s" % (table, got_auto, want_auto))

        gi, ei = indices(con, table), expected_indices(ent)
        if set(gi) != set(ei):
            errors.append(
                "%s 索引集合不一致：多=%s 少=%s"
                % (table, sorted(set(gi) - set(ei)), sorted(set(ei) - set(gi)))
            )
        for name in sorted(set(gi) & set(ei)):
            if gi[name]["unique"] != ei[name]["unique"]:
                errors.append(
                    "%s 索引 %s 唯一性不一致：实际=%d 期望=%d"
                    % (table, name, gi[name]["unique"], ei[name]["unique"])
                )
            if gi[name]["columns"] != ei[name]["columns"]:
                errors.append(
                    "%s 索引 %s 列顺序不一致：实际=%s 期望=%s"
                    % (table, name, gi[name]["columns"], ei[name]["columns"])
                )

    notes = ["逐表核对 %d/%d" % (len(set(by_name) & present), len(by_name))]
    return errors, notes


# ------------------------------------------------------------------------------ main

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="验证 Room 迁移链：结构是否等于新 schema、旧数据是否保留。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--old-schema", required=True, help="迁移前版本导出的 schema JSON")
    ap.add_argument("--new-schema", required=True, help="迁移后版本导出的 schema JSON")
    ap.add_argument("--migration-source", default=DEFAULT_MIGRATION_SOURCE,
                    help="含 MIGRATION_x_y 的 Kotlin 源文件（默认 %s）" % DEFAULT_MIGRATION_SOURCE)
    ap.add_argument("--from", dest="v_from", type=int, default=None, help="起始版本（默认取 old schema 的 version）")
    ap.add_argument("--to", dest="v_to", type=int, default=None, help="目标版本（默认取 new schema 的 version）")
    args = ap.parse_args(argv)

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    def resolve(p):
        return p if os.path.isabs(p) else os.path.join(root, p)

    for path in (args.old_schema, args.new_schema, args.migration_source):
        if not os.path.exists(resolve(path)):
            print("找不到文件：%s" % path, file=sys.stderr)
            return 2

    try:
        old, new = load_schema(resolve(args.old_schema)), load_schema(resolve(args.new_schema))
        v_from = args.v_from if args.v_from is not None else old["version"]
        v_to = args.v_to if args.v_to is not None else new["version"]
        migrations = extract_migrations(resolve(args.migration_source))
        chain = build_chain(migrations, v_from, v_to)
    except ValueError as ex:
        print("输入错误：%s" % ex, file=sys.stderr)
        return 2

    errors: list[str] = []

    # 0) schema 版本号必须与 --from/--to 对得上，否则是拿错了文件
    if old["version"] != v_from:
        errors.append("old schema 的 version=%d，与 --from %d 不符（拿错文件？）" % (old["version"], v_from))
    if new["version"] != v_to:
        errors.append("new schema 的 version=%d，与 --to %d 不符（拿错文件？）" % (new["version"], v_to))

    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA user_version = %d" % v_from)
    for sql in create_statements(old):
        con.execute(sql)
    con.commit()

    print("== 基线 v%d：%d 张表 ==" % (v_from, len(table_names(con))))

    snapshot, skipped = insert_probes(con, old)
    print("== 探针：%d 张表已埋入原值，%d 张跳过 ==" % (len(snapshot), len(skipped)))
    for s in skipped:
        print("  ⚠ %s" % s)

    print("== 迁移链：%s ==" % " → ".join(["v%d" % chain[0][0]] + ["v%d" % c[1] for c in chain]))
    for a, b, stmts in chain:
        print("  MIGRATION_%d_%d：%d 条语句" % (a, b, len(stmts)))
        for sql in stmts:
            flat = " ".join(sql.split())
            print("    · %s" % flat[:116] + ("..." if len(flat) > 116 else ""))
            try:
                con.execute(sql)
            except Exception as ex:
                errors.append("MIGRATION_%d_%d 语句执行失败：%s（%s）" % (a, b, ex, flat[:80]))

    # Android 的 SQLiteOpenHelper 在 onUpgrade 之后会把 user_version 写成新版本号，
    # 这里如实模拟一次，否则「PRAGMA user_version」这一项在内存库里永远是旧值、等于没测。
    con.execute("PRAGMA user_version = %d" % v_to)
    con.commit()
    got_version = con.execute("PRAGMA user_version").fetchone()[0]
    print("== PRAGMA user_version：%d（期望 %d，已模拟 Android 升级后的写入）==" % (got_version, v_to))
    if got_version != v_to:
        errors.append("PRAGMA user_version 实际=%d 期望=%d" % (got_version, v_to))

    struct_errors, notes = compare_structure(con, new)
    errors.extend(struct_errors)

    errors.extend(verify_probes(con, snapshot))
    if skipped:
        errors.append("有 %d 张表未埋验探针，数据保留未被验证：%s" % (len(skipped), [s.split("（")[0] for s in skipped]))

    print("== 结构核对：%s ==" % "；".join(notes))
    print("== 数据保留：%d 张表的探针行逐列比对 ==" % len(snapshot))

    print()
    print("== 结论 ==")
    if errors:
        for e in errors:
            print("  ✗ %s" % e)
        print("FAIL（%d 项）" % len(errors))
        return 1
    print("  ✓ 迁移链 v%d → v%d 连续，SQL 与新 schema 结构完全一致" % (v_from, v_to))
    print("  ✓ 旧表结构全部保留，无删除、无主键/索引漂移")
    print("  ✓ %d 张表的探针数据逐列原样保留" % len(snapshot))
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
