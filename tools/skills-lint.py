#!/usr/bin/env python3
"""iiBotMosire 工程 Skill 契约检查（.agents/skills/<name>/SKILL.md）。

只允许一层 active Skill 目录；校验 frontmatter、目录名、状态、owner、复核日、体量、
相对链接、@ 导入与目录登记。退出码：0=通过，1=发现不一致，2=用法/IO 错误。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

EXIT_PASS = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2
MAX_SKILL_BYTES = 32 * 1024
NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
INLINE_LINK_RE = re.compile(r"!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)")
FENCE_RE = re.compile(r"^[ \t]*(?P<fence>`{3,}|~{3,})[^\n]*\n.*?^[ \t]*(?P=fence)[ \t]*$", re.MULTILINE | re.DOTALL)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
SECRET_RES = [
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"sk-[A-Za-z0-9]{20,}"),
    re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----"),
]


@dataclass(frozen=True)
class Finding:
    code: str
    path: str
    line: int | None
    message: str

    def render(self) -> str:
        loc = self.path if self.line is None else f"{self.path}:{self.line}"
        return f"FAIL [{self.code}] {loc}: {self.message}"


def strip_code_keep_lines(text: str) -> str:
    def fence_repl(m: re.Match[str]) -> str:
        return "\n" * m.group(0).count("\n")

    def inline_repl(m: re.Match[str]) -> str:
        return " " * len(m.group(0))

    return INLINE_CODE_RE.sub(inline_repl, FENCE_RE.sub(fence_repl, text))


def parse_frontmatter(path: Path) -> tuple[dict[str, str], int, list[Finding]]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, 0, [Finding("SKILL_FRONTMATTER", path.as_posix(), 1, "缺少 frontmatter 起始 ---")]
    end = None
    for idx in range(1, len(lines)):
        if lines[idx].strip() == "---":
            end = idx
            break
    if end is None:
        return {}, 0, [Finding("SKILL_FRONTMATTER", path.as_posix(), 1, "缺少 frontmatter 结束 ---")]
    fields: dict[str, str] = {}
    for idx in range(1, end):
        line = lines[idx]
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if not m:
            return {}, end + 1, [Finding("SKILL_FRONTMATTER", path.as_posix(), idx + 1, "无法解析 frontmatter 行")]
        key, value = m.group(1), m.group(2).strip()
        if value.startswith(("'", '"')) and value.endswith(("'", '"')) and len(value) >= 2:
            value = value[1:-1]
        fields[key] = value
    return fields, end + 1, []


def check_links(repo: Path, skill_path: Path, text: str) -> list[Finding]:
    findings: list[Finding] = []
    root_resolved = repo.resolve()
    for m in INLINE_LINK_RE.finditer(strip_code_keep_lines(text)):
        target = m.group(1).strip()
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1].strip()
        lower = target.lower()
        if not target or target.startswith("#") or lower.startswith(
            ("http://", "https://", "mailto:", "tel:", "data:", "javascript:")
        ):
            continue
        target_path = urllib.parse.unquote(target.split("#", 1)[0].split("?", 1)[0]).strip()
        if not target_path or "{" in target_path or "}" in target_path:
            continue
        resolved = (skill_path.parent / target_path).resolve()
        try:
            resolved.relative_to(root_resolved)
        except ValueError:
            findings.append(Finding("SKILL_LINK_ESCAPE", skill_path.as_posix(), None, f"链接逃出仓库: {target}"))
            continue
        if not resolved.exists():
            findings.append(Finding("SKILL_DEAD_LINK", skill_path.as_posix(), None, f"相对链接不存在: {target}"))
    return findings


def check_skill(repo: Path, skill_dir: Path, catalog_text: str) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    rel_dir = skill_dir.relative_to(repo).as_posix()
    if skill_dir.is_symlink():
        return [Finding("SKILL_SYMLINK", rel_dir, None, "active Skill 目录不得是软链")], 0
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.is_file():
        return [Finding("SKILL_FILE_MISSING", rel_dir, None, "active 目录必须有 SKILL.md")], 0
    if skill_md.stat().st_size > MAX_SKILL_BYTES:
        findings.append(Finding("SKILL_TOO_LARGE", skill_md.relative_to(repo).as_posix(), None, f"超过 {MAX_SKILL_BYTES} bytes"))
    if not NAME_RE.match(skill_dir.name):
        findings.append(Finding("SKILL_DIR_NAME", rel_dir, None, "目录名必须是 kebab-case"))
    fields, body_start, fm_findings = parse_frontmatter(skill_md)
    findings.extend(fm_findings)
    text = skill_md.read_text(encoding="utf-8")
    required = ("name", "description", "status", "owner", "last_reviewed")
    for key in required:
        if not fields.get(key):
            findings.append(Finding("SKILL_FIELD_MISSING", skill_md.relative_to(repo).as_posix(), None, f"frontmatter 缺少 {key}"))
    if fields.get("name") and fields["name"] != skill_dir.name:
        findings.append(Finding("SKILL_NAME_MISMATCH", skill_md.relative_to(repo).as_posix(), None, "frontmatter name 与目录名不一致"))
    if fields.get("status") != "active":
        findings.append(Finding("SKILL_STATUS", skill_md.relative_to(repo).as_posix(), None, "active 目录中的 Skill 必须 status: active"))
    if fields.get("last_reviewed") and not DATE_RE.match(fields["last_reviewed"]):
        findings.append(Finding("SKILL_REVIEW_DATE", skill_md.relative_to(repo).as_posix(), None, "last_reviewed 必须是 YYYY-MM-DD"))
    if len(fields.get("description", "")) < 8:
        findings.append(Finding("SKILL_DESCRIPTION", skill_md.relative_to(repo).as_posix(), None, "description 太短或缺失"))
    if f"`{skill_dir.name}`" not in catalog_text:
        findings.append(Finding("SKILL_NOT_CATALOGED", rel_dir, None, "未在 docs/agents/SKILLS.md 登记"))
    for no, line in enumerate(text.splitlines(), start=1):
        if re.match(r"^\s*@[^\s]", line):
            findings.append(Finding("SKILL_AT_IMPORT", skill_md.relative_to(repo).as_posix(), no, "Skill 不得用 @ 导入长文"))
        for secret_re in SECRET_RES:
            if secret_re.search(line):
                findings.append(Finding("SKILL_SECRET", skill_md.relative_to(repo).as_posix(), no, "疑似密钥/私钥内容"))
                break
    findings.extend(check_links(repo, skill_md, text))
    for path in sorted(skill_dir.rglob("SKILL.md")):
        if path != skill_md:
            findings.append(Finding("SKILL_DEEP_FILE", path.relative_to(repo).as_posix(), None, "SKILL.md 只允许一层"))
    return findings, body_start


def run_checks(repo: Path) -> tuple[list[Finding], int]:
    root = repo / ".agents/skills"
    findings: list[Finding] = []
    count = 0
    if not root.exists():
        return findings, 0
    if root.is_symlink():
        return [Finding("SKILLS_ROOT_SYMLINK", ".agents/skills", None, "active Skill 根不得是软链")], 0
    if not root.is_dir():
        return [Finding("SKILLS_ROOT_NOT_DIR", ".agents/skills", None, "active Skill 根必须是目录")], 0
    catalog = repo / "docs/agents/SKILLS.md"
    if not catalog.is_file():
        return [Finding("SKILLS_CATALOG_MISSING", "docs/agents/SKILLS.md", None, "Skill 目录文件不存在")], 0
    catalog_text = catalog.read_text(encoding="utf-8")
    for entry in sorted(root.iterdir()):
        if not entry.is_dir():
            findings.append(Finding("SKILL_ENTRY", entry.relative_to(repo).as_posix(), None, "active 根下只允许目录"))
            continue
        count += 1
        skill_findings, _ = check_skill(repo, entry, catalog_text)
        findings.extend(skill_findings)
    for path in sorted(root.rglob("SKILL.md")):
        if len(path.relative_to(root).parts) != 2:
            findings.append(Finding("SKILL_DEEP_FILE", path.relative_to(repo).as_posix(), None, "SKILL.md 必须位于 .agents/skills/<name>/ 下"))
    return findings, count


def make_fixture(root: Path) -> None:
    (root / "docs/agents").mkdir(parents=True)
    (root / "docs/agents/SKILLS.md").write_text("# Skills\n\n| `demo` | active |\n", encoding="utf-8")
    skill = root / ".agents/skills/demo"
    skill.mkdir(parents=True)
    (skill / "SKILL.md").write_text(
        """---
name: demo
description: A valid demo skill for selftest.
status: active
owner: repo-maintainers
last_reviewed: 2026-01-01
---

# Demo

## 何时使用
演示。
""",
        encoding="utf-8",
    )


def selftest_case(name: str, mutate, expected: str | None) -> bool:
    with tempfile.TemporaryDirectory(prefix="skills-lint-selftest-") as tmp:
        root = Path(tmp) / "repo"
        make_fixture(root)
        mutate(root)
        findings, _ = run_checks(root)
        codes = {f.code for f in findings}
        ok = (not findings) if expected is None else expected in codes
        if ok:
            print(f"SELFTEST PASS skills-lint.py {name} (findings={len(findings)})")
        else:
            print(f"SELFTEST FAIL skills-lint.py {name}: expected={expected or 'PASS'} codes={sorted(codes)}")
            for f in findings:
                print("  " + f.render())
        return ok


def run_selftest() -> int:
    def no_front(root: Path) -> None:
        p = root / ".agents/skills/demo/SKILL.md"
        p.write_text("# no frontmatter\n", encoding="utf-8")

    def name_mismatch(root: Path) -> None:
        p = root / ".agents/skills/demo/SKILL.md"
        p.write_text(p.read_text(encoding="utf-8").replace("name: demo", "name: other"), encoding="utf-8")

    def status_drafted(root: Path) -> None:
        p = root / ".agents/skills/demo/SKILL.md"
        p.write_text(p.read_text(encoding="utf-8").replace("status: active", "status: drafted"), encoding="utf-8")

    def dead_link(root: Path) -> None:
        p = root / ".agents/skills/demo/SKILL.md"
        p.write_text(p.read_text(encoding="utf-8") + "\n[dead](missing.md)\n", encoding="utf-8")

    def not_cataloged(root: Path) -> None:
        (root / "docs/agents/SKILLS.md").write_text("# Skills\n", encoding="utf-8")

    cases = [
        selftest_case("valid-green", lambda _root: None, None),
        selftest_case("no-frontmatter-red", no_front, "SKILL_FRONTMATTER"),
        selftest_case("name-mismatch-red", name_mismatch, "SKILL_NAME_MISMATCH"),
        selftest_case("status-drafted-red", status_drafted, "SKILL_STATUS"),
        selftest_case("dead-link-red", dead_link, "SKILL_DEAD_LINK"),
        selftest_case("not-cataloged-red", not_cataloged, "SKILL_NOT_CATALOGED"),
    ]
    passed = sum(cases)
    total = len(cases)
    if passed == total:
        print(f"SELFTEST PASSED skills-lint.py ({passed}/{total} cases)")
        return EXIT_PASS
    print(f"SELFTEST FAILED skills-lint.py ({passed}/{total} cases)")
    return EXIT_FINDINGS


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="SimulatorMosire engineering skill contract check")
    parser.add_argument("--root", type=Path, help="repository root (default: script parent repo)")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return run_selftest()
    root = (args.root or Path(__file__).resolve().parent.parent).resolve()
    if not root.is_dir():
        print(f"skills-lint.py: root does not exist: {root}", file=sys.stderr)
        return EXIT_USAGE
    findings, count = run_checks(root)
    if findings:
        for finding in findings:
            print(finding.render())
        print(f"SKILLS LINT FAILED ({len(findings)} findings; active skills={count})")
        return EXIT_FINDINGS
    print(f"SKILLS LINT PASSED (active skills={count})")
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
