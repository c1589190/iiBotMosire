#!/usr/bin/env python3
"""iiBotMosire 文档一致性检查（项目维护机械门禁）。

当前项目处于 pre-Maven 阶段：没有根 pom.xml 时仍必须完整检查文档体系。
Maven 工程落地后，自动增加“模块存在于根 AGENTS + 模块自身有 AGENTS.md”的检查。

退出码：0=通过，1=发现不一致，2=用法/IO 错误。
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

POM_NS = "{http://maven.apache.org/POM/4.0.0}"
INLINE_LINK_RE = re.compile(r"!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\s*\)")
REFERENCE_LINK_RE = re.compile(r"^\[[^\]\n]+\]:\s*(<[^>\n]+>|\S+)", re.MULTILINE)
FENCE_RE = re.compile(r"^[ \t]*(?P<fence>`{3,}|~{3,})[^\n]*\n.*?^[ \t]*(?P=fence)[ \t]*$", re.MULTILINE | re.DOTALL)
INLINE_CODE_RE = re.compile(r"`[^`\n]*`")
AT_IMPORT_RE = re.compile(r"^\s*@[^\s]")
MODULE_COUNT_RE = re.compile(r"(\d+)\s*个\s*(?:`iibot-\*`\s*)?模块")
DATED_STATUS_LINK = "../../agents/STATUS.md"

BASE_DOCS = [
    "AGENTS.md",
    "README.md",
    "docs/AGENTS.md",
    "docs/agents/AGENTS.md",
    "docs/agents/STATUS.md",
    "docs/agents/SKILLS.md",
    "docs/agents/MAINTENANCE_RULINGS.md",
    "docs/agents/WORKFLOW.md",
    "docs/agents/VERIFICATION.md",
    "docs/agents/ENVIRONMENT.md",
    "docs/superpowers/AGENTS.md",
    "docs/superpowers/DESIGN_RULINGS.md",
    "docs/superpowers/specs/AGENTS.md",
    "docs/superpowers/plans/AGENTS.md",
    "docs/superpowers/reports/AGENTS.md",
    "docs/superpowers/status/AGENTS.md",
    ".agents/AGENTS.md",
    ".agents/notes/README.md",
    ".superpowers/AGENTS.md",
    "tools/AGENTS.md",
]

EXIT_PASS = 0
EXIT_FINDINGS = 1
EXIT_USAGE = 2


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


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def load_modules(root: Path) -> tuple[list[str], list[Finding], bool]:
    """返回 (modules, findings, pom_exists)。

    pre-Maven 阶段没有 pom.xml 是合法状态；一旦存在 pom.xml，就必须可解析且 modules 非空。
    """
    pom = root / "pom.xml"
    if not pom.is_file():
        return [], [], False
    try:
        xml = ET.parse(pom)
    except ET.ParseError as exc:
        return [], [Finding("POM_PARSE", "pom.xml", None, f"XML 解析失败: {exc}")], True
    modules: list[str] = []
    for node in xml.getroot().findall(f"{POM_NS}modules/{POM_NS}module"):
        name = (node.text or "").strip()
        if name:
            modules.append(name)
    findings: list[Finding] = []
    if not modules:
        findings.append(Finding("POM_MODULES", "pom.xml", None, "<modules> 为空"))
    return modules, findings, True


def all_markdown(root: Path) -> list[Path]:
    excluded_parts = {".git", "target", "worktree", ".work", "archive"}
    files: list[Path] = []
    for path in root.rglob("*.md"):
        rel = path.relative_to(root)
        if any(part in excluded_parts for part in rel.parts):
            continue
        if rel.parts[:2] == (".superpowers", "evidence") or rel.parts[:2] == (".superpowers", "sdd"):
            continue
        files.append(path)
    return sorted(files)


def check_base_files(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for rel in BASE_DOCS:
        if not (root / rel).is_file():
            findings.append(Finding("FILE_MISSING", rel, None, "必需文档不存在"))
    return findings


def check_modules(root: Path, modules: list[str]) -> list[Finding]:
    if not modules:
        return []
    findings: list[Finding] = []
    root_agents = root / "AGENTS.md"
    if not root_agents.is_file():
        return [Finding("FILE_MISSING", "AGENTS.md", None, "根 AGENTS.md 不存在")]
    text = read_text(root_agents)
    for module in modules:
        if module not in text:
            findings.append(Finding("MODULE_MISSING", "AGENTS.md", None, f"根索引缺少模块: {module}"))
        local = root / module / "AGENTS.md"
        if not local.is_file():
            findings.append(Finding("MODULE_AGENTS_MISSING", f"{module}/AGENTS.md", None, "模块局部 AGENTS.md 不存在"))
            continue
        local_text = read_text(local)
        for marker in ("docs/agents/STATUS.md", "docs/superpowers/AGENTS.md", "职责", "验证"):
            if marker not in local_text:
                findings.append(
                    Finding("MODULE_AGENTS_CONTENT", f"{module}/AGENTS.md", None, f"缺少局部索引标记: {marker}")
                )
    readme = root / "README.md"
    if readme.is_file():
        m = MODULE_COUNT_RE.search(read_text(readme))
        if m and int(m.group(1)) != len(modules):
            findings.append(
                Finding(
                    "README_MODULE_COUNT",
                    "README.md",
                    None,
                    f"README 模块数未与 pom 对齐（pom={len(modules)}）",
                )
            )
    return findings


def check_links(root: Path, files: list[Path]) -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    checked = 0
    root_resolved = root.resolve()
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        text = strip_code_keep_lines(read_text(path))
        matches: list[tuple[int, str]] = []
        matches.extend((m.start(), m.group(1)) for m in INLINE_LINK_RE.finditer(text))
        matches.extend((m.start(), m.group(1)) for m in REFERENCE_LINK_RE.finditer(text))
        for pos, raw_target in matches:
            target = raw_target.strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1].strip()
            lower = target.lower()
            if not target or target.startswith("#") or lower.startswith(
                ("http://", "https://", "mailto:", "tel:", "data:", "javascript:")
            ):
                continue
            if "{" in target or "}" in target:
                continue
            target_path = target.split("#", 1)[0].split("?", 1)[0]
            target_path = urllib.parse.unquote(target_path).strip()
            if not target_path:
                continue
            checked += 1
            line = text.count("\n", 0, pos) + 1
            if target_path.startswith("/"):
                findings.append(Finding("LINK_ABSOLUTE", rel, line, f"只允许仓库内相对链接: {target}"))
                continue
            resolved = (path.parent / target_path).resolve()
            try:
                resolved.relative_to(root_resolved)
            except ValueError:
                findings.append(Finding("LINK_ESCAPE", rel, line, f"链接逃出仓库: {target}"))
                continue
            if not resolved.exists():
                findings.append(Finding("DEAD_LINK", rel, line, f"相对链接目标不存在: {target}"))
    return findings, checked


def check_status(root: Path) -> list[Finding]:
    status = root / "docs/agents/STATUS.md"
    if not status.is_file():
        return [Finding("STATUS_MISSING", "docs/agents/STATUS.md", None, "唯一实时状态文件不存在")]
    text = read_text(status)
    for marker in ("更新时间", "当前目标", "最近功能门禁", "未验证", "下一步"):
        if marker not in text:
            return [Finding("STATUS_CONTENT", "docs/agents/STATUS.md", None, f"缺少字段: {marker}")]
    return []


def check_split(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    maint = root / "docs/agents/MAINTENANCE_RULINGS.md"
    design = root / "docs/superpowers/DESIGN_RULINGS.md"
    rules = root / "docs/AGENTS.md"
    for path in (maint, design, rules):
        if not path.is_file():
            findings.append(Finding("SPLIT_FILE_MISSING", path.relative_to(root).as_posix(), None, "分账文件不存在"))
            continue
        text = read_text(path)
        if "项目设计" not in text or "项目维护" not in text:
            findings.append(
                Finding("SPLIT_MARKER_MISSING", path.relative_to(root).as_posix(), None, "缺少项目设计/项目维护分账标记")
            )
    if maint.is_file() and "../superpowers/" not in read_text(maint) and "docs/superpowers/" not in read_text(maint):
        findings.append(Finding("SPLIT_BACKLINK", "docs/agents/MAINTENANCE_RULINGS.md", None, "缺少指向设计侧的链接"))
    return findings


def check_dated_status(root: Path) -> list[Finding]:
    status_dir = root / "docs/superpowers/status"
    if not status_dir.is_dir():
        return [Finding("STATUS_DIR_MISSING", "docs/superpowers/status", None, "历史状态目录不存在")]
    findings: list[Finding] = []
    for path in sorted(status_dir.glob("*.md")):
        if path.name == "AGENTS.md":
            continue
        text = read_text(path)
        rel = path.relative_to(root).as_posix()
        if "历史快照" not in text:
            findings.append(Finding("DATED_STATUS_MARKER", rel, None, "dated status 必须在头部标“历史快照”"))
        if "docs/agents/STATUS.md" not in text and DATED_STATUS_LINK not in text:
            findings.append(Finding("DATED_STATUS_BACKLINK", rel, None, "dated status 必须链接当前 STATUS"))
    return findings


def check_root_index(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    agents = root / "AGENTS.md"
    if not agents.is_file():
        return [Finding("ROOT_AGENTS_MISSING", "AGENTS.md", None, "根 AGENTS.md 不存在")]
    text = read_text(agents)
    if agents.stat().st_size > 20000:
        findings.append(Finding("ROOT_AGENTS_TOO_LARGE", "AGENTS.md", None, "根 AGENTS 超过 20KB，索引膨胀"))
    if "docs/superpowers/status/" in text:
        findings.append(Finding("ROOT_STATUS_POINTER", "AGENTS.md", None, "根入口不得把 dated status 当当前状态"))
    if re.search(r"(最新|唯一).*(入口|未完成清单)|下次开工的唯一入口", text):
        findings.append(Finding("ROOT_LATEST_POINTER", "AGENTS.md", None, "根入口不得写最新/唯一入口"))
    if "docs/agents/STATUS.md" not in text:
        findings.append(Finding("ROOT_STATUS_LINK", "AGENTS.md", None, "根索引必须链接当前 STATUS"))
    readme = root / "README.md"
    if readme.is_file() and "当前阶段" not in read_text(readme):
        findings.append(Finding("README_PHASE", "README.md", None, "README 必须写当前阶段"))
    return findings


def check_at_imports(root: Path, files: list[Path]) -> list[Finding]:
    findings: list[Finding] = []
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        for no, line in enumerate(read_text(path).splitlines(), start=1):
            if AT_IMPORT_RE.match(line):
                findings.append(Finding("AT_IMPORT", rel, no, "@ 导入会展开长文，改用链接"))
    return findings


def check_worktree_policy(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    gitignore = root / ".gitignore"
    if not gitignore.is_file() or "/worktree/" not in read_text(gitignore):
        findings.append(Finding("WORKTREE_POLICY", ".gitignore", None, "缺少 /worktree/ 忽略规则"))
    env = root / "docs/agents/ENVIRONMENT.md"
    if not env.is_file() or "worktree/<name>" not in read_text(env):
        findings.append(Finding("WORKTREE_POLICY", "docs/agents/ENVIRONMENT.md", None, "缺少 worktree/<name> 规范"))
    agents = root / "AGENTS.md"
    if agents.is_file() and "worktree/<name>" not in read_text(agents):
        findings.append(Finding("WORKTREE_POLICY", "AGENTS.md", None, "根索引缺少 worktree/<name> 规则指针"))
    return findings


def check_skills_catalog(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    catalog = root / "docs/agents/SKILLS.md"
    if not catalog.is_file():
        return [Finding("SKILLS_CATALOG_MISSING", "docs/agents/SKILLS.md", None, "Skill 目录不存在")]
    text = read_text(catalog)
    skills = root / ".agents/skills"
    if not skills.is_dir():
        return findings
    for entry in sorted(skills.iterdir()):
        if not entry.is_dir():
            continue
        if not (entry / "SKILL.md").is_file():
            findings.append(Finding("SKILL_FILE_MISSING", entry.relative_to(root).as_posix(), None, "active 目录缺 SKILL.md"))
            continue
        if f"`{entry.name}`" not in text:
            findings.append(Finding("SKILL_NOT_CATALOGED", entry.relative_to(root).as_posix(), None, "active Skill 未登记到 SKILLS.md"))
    return findings


def check_spec_headers(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    specs = root / "docs/superpowers/specs"
    if not specs.is_dir():
        return [Finding("SPECS_DIR_MISSING", "docs/superpowers/specs", None, "设计目录不存在")]
    for path in sorted(specs.glob("*.md")):
        if path.name == "AGENTS.md":
            continue
        rel = path.relative_to(root).as_posix()
        text = read_text(path)
        if "> **状态**" not in text:
            findings.append(Finding("SPEC_STATUS_HEADER", rel, None, "设计文档缺少状态头"))
        if "用户原话" not in text and "用户裁定" not in text:
            findings.append(Finding("SPEC_USER_SOURCE", rel, None, "设计文档缺少用户原话/裁定来源"))
        if "验收" not in text:
            findings.append(Finding("SPEC_ACCEPTANCE", rel, None, "设计文档缺少验收判据"))
    return findings


def run_checks(root: Path) -> tuple[list[Finding], dict[str, int | str]]:
    modules, findings, pom_exists = load_modules(root)
    findings.extend(check_base_files(root))
    findings.extend(check_modules(root, modules))
    markdown = all_markdown(root)
    link_findings, links = check_links(root, markdown)
    findings.extend(link_findings)
    findings.extend(check_status(root))
    findings.extend(check_split(root))
    findings.extend(check_dated_status(root))
    findings.extend(check_root_index(root))
    findings.extend(check_at_imports(root, markdown))
    findings.extend(check_worktree_policy(root))
    findings.extend(check_skills_catalog(root))
    findings.extend(check_spec_headers(root))
    stats: dict[str, int | str] = {
        "phase": "maven" if pom_exists else "pre-maven",
        "modules": len(modules),
        "files": len(markdown),
        "links": links,
    }
    return findings, stats


def make_fixture(root: Path, with_pom: bool = False) -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / "docs/agents").mkdir(parents=True)
    (root / "docs/superpowers/specs").mkdir(parents=True)
    (root / "docs/superpowers/status").mkdir(parents=True)
    (root / ".agents/notes").mkdir(parents=True)
    (root / ".superpowers").mkdir(parents=True)
    (root / "tools").mkdir(parents=True)

    (root / "AGENTS.md").write_text(
        "# AGENTS\n\n项目简介。\n\n当前状态：[STATUS](docs/agents/STATUS.md)。\n\nworktree/<name>\n",
        encoding="utf-8",
    )
    (root / "README.md").write_text(
        "# README\n\n当前阶段：pre-Maven。\n\n[STATUS](docs/agents/STATUS.md)\n",
        encoding="utf-8",
    )
    (root / ".gitignore").write_text("/worktree/\n", encoding="utf-8")
    (root / "docs/AGENTS.md").write_text(
        "# docs\n\n项目设计在 superpowers；项目维护在 agents。\n\n[STATUS](agents/STATUS.md)\n",
        encoding="utf-8",
    )
    (root / "docs/agents/AGENTS.md").write_text(
        "# agents\n\n[STATUS](STATUS.md) [设计](../superpowers/AGENTS.md)\n",
        encoding="utf-8",
    )
    (root / "docs/agents/STATUS.md").write_text(
        """# STATUS

> 更新时间：2026-01-01 00:00（Asia/Shanghai）
> 最近功能门禁：NOT_RUN

## 当前目标
自检

## 未验证
无

## 下一步
无
""",
        encoding="utf-8",
    )
    (root / "docs/agents/MAINTENANCE_RULINGS.md").write_text(
        "# 维护裁定\n\n项目设计在 `docs/superpowers/DESIGN_RULINGS.md`；项目维护在本文。\n\n## M-001\n\n有效。\n",
        encoding="utf-8",
    )
    (root / "docs/agents/SKILLS.md").write_text("# Skills\n\n当前 active Skill = 0。\n", encoding="utf-8")
    for name in ("WORKFLOW.md", "VERIFICATION.md", "ENVIRONMENT.md"):
        body = "# ENVIRONMENT\n\nworktree/<name>\n" if name == "ENVIRONMENT.md" else f"# {name}\n"
        (root / "docs/agents" / name).write_text(body, encoding="utf-8")
    (root / "docs/superpowers/AGENTS.md").write_text("# 设计索引\n\n项目设计入口。\n", encoding="utf-8")
    (root / "docs/superpowers/DESIGN_RULINGS.md").write_text(
        "# 设计裁定\n\n项目设计；项目维护见 `../agents/MAINTENANCE_RULINGS.md`。\n",
        encoding="utf-8",
    )
    (root / "docs/superpowers/specs/AGENTS.md").write_text("# specs\n", encoding="utf-8")
    (root / "docs/superpowers/specs/2026-01-01-fixture.md").write_text(
        "# Fixture\n\n> **状态**：提议\n> **用户原话**：fixture\n\n## 验收\n\nfixture\n",
        encoding="utf-8",
    )
    (root / "docs/superpowers/plans").mkdir()
    (root / "docs/superpowers/plans/AGENTS.md").write_text("# plans\n", encoding="utf-8")
    (root / "docs/superpowers/reports").mkdir()
    (root / "docs/superpowers/reports/AGENTS.md").write_text("# reports\n", encoding="utf-8")
    (root / "docs/superpowers/status/AGENTS.md").write_text("# status\n", encoding="utf-8")
    (root / "docs/superpowers/status/2026-01-01-old.md").write_text(
        "# 历史\n\n> 历史快照。\n\n当前状态：[STATUS](../../agents/STATUS.md)\n",
        encoding="utf-8",
    )
    (root / ".agents/AGENTS.md").write_text("# .agents\n", encoding="utf-8")
    (root / ".agents/notes/README.md").write_text("# notes\n", encoding="utf-8")
    (root / ".superpowers/AGENTS.md").write_text("# local evidence\n", encoding="utf-8")
    (root / "tools/AGENTS.md").write_text("# tools\n", encoding="utf-8")

    if with_pom:
        (root / "pom.xml").write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>test</groupId>
  <artifactId>fixture</artifactId>
  <version>0.0.0</version>
  <packaging>pom</packaging>
  <modules>
    <module>iibot-a</module>
  </modules>
</project>
""",
            encoding="utf-8",
        )
        (root / "iibot-a").mkdir()
        (root / "iibot-a/AGENTS.md").write_text(
            "# iibot-a\n\n职责：fixture。\n\n验证：fixture。\n\n[状态](../docs/agents/STATUS.md) [设计](../docs/superpowers/AGENTS.md)\n",
            encoding="utf-8",
        )
        (root / "AGENTS.md").write_text(
            read_text(root / "AGENTS.md") + "\n模块：`iibot-a`。\n", encoding="utf-8"
        )


def selftest_case(name: str, mutate, expected: str | None) -> bool:
    with tempfile.TemporaryDirectory(prefix="check-docs-selftest-") as tmp:
        root = Path(tmp) / "repo"
        make_fixture(root)
        mutate(root)
        findings, _ = run_checks(root)
        codes = {f.code for f in findings}
        ok = (not findings) if expected is None else expected in codes
        if ok:
            print(f"SELFTEST PASS check-docs.py {name} (findings={len(findings)})")
        else:
            print(f"SELFTEST FAIL check-docs.py {name}: expected={expected or 'PASS'} codes={sorted(codes)}")
            for finding in findings:
                print("  " + finding.render())
        return ok


def run_selftest() -> int:
    def dead_link(root: Path) -> None:
        with (root / "docs/agents/STATUS.md").open("a", encoding="utf-8") as fh:
            fh.write("\n[dead](../nope.md)\n")

    def status_missing(root: Path) -> None:
        (root / "docs/agents/STATUS.md").unlink()

    def root_pointer(root: Path) -> None:
        p = root / "AGENTS.md"
        p.write_text(p.read_text(encoding="utf-8") + "\n最新入口：docs/superpowers/status/2026-01-01-old.md\n", encoding="utf-8")

    def split_missing(root: Path) -> None:
        (root / "docs/AGENTS.md").write_text("# no split\n", encoding="utf-8")

    def worktree_policy_missing(root: Path) -> None:
        (root / ".gitignore").write_text("", encoding="utf-8")

    def dated_status_missing_marker(root: Path) -> None:
        (root / "docs/superpowers/status/2026-01-01-old.md").write_text("# 历史\n", encoding="utf-8")

    def spec_missing_header(root: Path) -> None:
        (root / "docs/superpowers/specs/2026-01-01-fixture.md").write_text("# bad\n", encoding="utf-8")

    def at_import(root: Path) -> None:
        with (root / "docs/agents/STATUS.md").open("a", encoding="utf-8") as fh:
            fh.write("\n@docs/AGENTS.md\n")

    def skill_not_cataloged(root: Path) -> None:
        d = root / ".agents/skills/foo"
        d.mkdir(parents=True)
        (d / "SKILL.md").write_text("# foo\n", encoding="utf-8")

    def module_agents_missing(root: Path) -> None:
        (root / "pom.xml").write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>test</groupId>
  <artifactId>fixture</artifactId>
  <version>0.0.0</version>
  <packaging>pom</packaging>
  <modules><module>iibot-a</module></modules>
</project>
""",
            encoding="utf-8",
        )
        (root / "iibot-a").mkdir()
        (root / "iibot-a/AGENTS.md").write_text("# iibot-a\n", encoding="utf-8")
        (root / "AGENTS.md").write_text(
            read_text(root / "AGENTS.md") + "\n模块：`iibot-a`。\n", encoding="utf-8"
        )
        (root / "iibot-a/AGENTS.md").unlink()

    cases = [
        selftest_case("valid-green", lambda _root: None, None),
        selftest_case("dead-link-red", dead_link, "DEAD_LINK"),
        selftest_case("status-missing-red", status_missing, "STATUS_MISSING"),
        selftest_case("root-status-pointer-red", root_pointer, "ROOT_STATUS_POINTER"),
        selftest_case("split-missing-red", split_missing, "SPLIT_MARKER_MISSING"),
        selftest_case("worktree-policy-red", worktree_policy_missing, "WORKTREE_POLICY"),
        selftest_case("dated-status-marker-red", dated_status_missing_marker, "DATED_STATUS_MARKER"),
        selftest_case("spec-header-red", spec_missing_header, "SPEC_STATUS_HEADER"),
        selftest_case("at-import-red", at_import, "AT_IMPORT"),
        selftest_case("skill-not-cataloged-red", skill_not_cataloged, "SKILL_NOT_CATALOGED"),
        selftest_case("module-agents-missing-red", module_agents_missing, "MODULE_AGENTS_MISSING"),
    ]
    passed = sum(cases)
    total = len(cases)
    if passed == total:
        print(f"SELFTEST PASSED check-docs.py ({passed}/{total} cases)")
        return EXIT_PASS
    print(f"SELFTEST FAILED check-docs.py ({passed}/{total} cases)")
    return EXIT_FINDINGS


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="iiBotMosire docs consistency check")
    parser.add_argument("--root", type=Path, help="repository root (default: script parent repo)")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return run_selftest()
    root = (args.root or Path(__file__).resolve().parent.parent).resolve()
    if not root.is_dir():
        print(f"check-docs.py: root does not exist: {root}", file=sys.stderr)
        return EXIT_USAGE
    findings, stats = run_checks(root)
    if findings:
        for finding in findings:
            print(finding.render())
        print(
            f"DOCS CHECK FAILED ({len(findings)} findings; phase={stats['phase']} modules={stats['modules']} "
            f"files={stats['files']} links={stats['links']})"
        )
        return EXIT_FINDINGS
    print(
        f"DOCS CHECK PASSED (phase={stats['phase']} modules={stats['modules']} "
        f"files={stats['files']} links={stats['links']})"
    )
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
