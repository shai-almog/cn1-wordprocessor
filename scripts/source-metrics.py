#!/usr/bin/env python3
"""Auditable, scope-labelled source LOC/LLM-token inventory for Java/CN1 and Wordcraft."""
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

import tiktoken
from tree_sitter import Language, Parser
import tree_sitter_rust

TEST_ATTRIBUTE = re.compile(rb'#\[\s*(?:cfg\s*\(\s*test\s*\)|(?:\w+::)?test(?:\([^\]]*\))?)\s*\]$')
PARSER = Parser(Language(tree_sitter_rust.language()))


def rust_test_regions(data):
    """Find explicit test items via syntax, including braces in strings/comments safely."""
    tree = PARSER.parse(data)
    if tree.root_node.has_error:
        raise ValueError('Rust syntax parser reported an error; test-pruned estimate refused')
    regions, external = [], []

    def visit(node, module_prefix=()):
        attributes = []
        for child in node.named_children:
            if child.type == 'attribute_item':
                attributes.append(child)
                continue
            if child.type in ('line_comment', 'block_comment'):
                continue
            attrs = [data[a.start_byte:a.end_byte] for a in attributes]
            test_only = any(TEST_ATTRIBUTE.fullmatch(a.strip()) for a in attrs)
            name_node = child.child_by_field_name('name') if child.type == 'mod_item' else None
            name = name_node.text.decode() if name_node else None
            body = child.child_by_field_name('body') if name else None
            if test_only:
                regions.append((attributes[0].start_byte, child.end_byte))
                if name and body is None:
                    override = next((re.search(rb'path\s*=\s*"([^"]+)"', a) for a in attrs if b'path' in a), None)
                    external.append((module_prefix + (name,), override.group(1).decode() if override else None))
            elif name and body:
                visit(body, module_prefix + (name,))
            else:
                visit(child, module_prefix)
            attributes = []
    visit(tree.root_node)
    return regions, external


def prune(data, regions):
    parts, offset = [], 0
    for start, end in sorted(regions):
        if start < offset:
            continue
        parts.append(data[offset:start])
        offset = end
    parts.append(data[offset:])
    return b''.join(parts)


def git_revision(root):
    r = subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None


def group_java(path):
    parts = path.parts
    if parts[0] == 'benchmarks':
        return 'benchmarks'
    if path.name.startswith('test-'):
        return 'tests'
    if 'src' not in parts or path.suffix != '.java':
        return 'buildAndAutomation'
    if parts[0] == 'benchmarks':
        return 'benchmarks'
    if 'test' in parts:
        return 'tests'
    if parts[0] == 'app':
        return 'platformGlue'
    return 'application'


def group_rust(path):
    if path.suffix != '.rs':
        return 'buildAndAutomation'
    if 'tests' in path.parts:
        return 'tests'
    if 'benches' in path.parts:
        return 'benchmarks'
    if 'examples' in path.parts:
        return 'examples'
    if path.parts[0] == 'xtask' or path.name == 'build.rs':
        return 'buildAndAutomation'
    return 'application'


def source_files(root, side):
    if side == 'java':
        files = [p for top in ['document','layout','formats','automation','editor','desktop','app','benchmarks']
                 for p in (root/top).rglob('*.java') if 'target' not in p.parts]
        files += list((root/'benchmarks').glob('*.rs'))
        files += list((root/'scripts').glob('*.py'))+list((root/'scripts').glob('*.sh'))
        files += [root/'scripts/wordprocessor']
        files += [p for p in root.rglob('pom.xml') if 'target' not in p.parts]
    else:
        # Only tracked source: dependencies, build outputs and downloaded assets are not app source.
        tracked = subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().split('\0')
        files = [root/p for p in tracked if p and
                 (Path(p).suffix == '.rs' or Path(p).name in ('Cargo.toml','Trunk.toml'))]
    return sorted(set(p for p in files if p.is_file()))


def measure(root, side, encoding, work):
    files = source_files(root, side)
    groups = {}
    manifest = []
    test_modules = set()
    rust_regions = {}
    unpruned_files = []
    if side == 'rust':
        for path in files:
            if path.suffix != '.rs':
                continue
            data = path.read_bytes()
            try:
                regions, external = rust_test_regions(data)
            except ValueError as e:
                unpruned_files.append({'path':str(path.relative_to(root)),'reason':str(e)})
                regions, external = [], []
            rust_regions[path] = regions
            base = path.parent if path.stem in ('lib','main','mod') else path.with_suffix('')
            for names, override in external:
                candidates = [path.parent/override] if override else [base.joinpath(*names).with_suffix('.rs'), base.joinpath(*names)/'mod.rs']
                for candidate in candidates:
                    if candidate.is_file():
                        test_modules.add(candidate)
                        if candidate.name == 'mod.rs':
                            test_modules.update(candidate.parent.rglob('*.rs'))
                        else:
                            test_modules.update(candidate.with_suffix('').rglob('*.rs'))
    for path in files:
        relative = path.relative_to(root)
        category = group_java(relative) if side == 'java' else group_rust(relative)
        if path in test_modules:
            category = 'tests'
        data = path.read_bytes()
        regions = rust_regions.get(path, []) if category == 'application' else []
        production = prune(data, regions)
        token_count = len(encoding.encode(data.decode('utf-8'), disallowed_special=()))
        kept_tokens = len(encoding.encode(production.decode('utf-8'), disallowed_special=()))
        record = {'path':str(relative), 'category':category, 'bytes':len(data),
                  'sha256':hashlib.sha256(data).hexdigest(), 'sourceTokens':token_count,
                  'explicitTestItemsRemoved':len(regions), 'testPrunedTokens':kept_tokens}
        manifest.append(record)
        g = groups.setdefault(category, {'files':0,'bytes':0,'sourceTokens':0,'testPrunedTokens':0,'explicitTestItemsRemoved':0})
        for field in ['bytes','sourceTokens','testPrunedTokens','explicitTestItemsRemoved']:
            g[field] += record[field]
        g['files'] += 1
        # Separate raw and test-pruned trees let cloc report actual code lines consistently.
        for variant, content in [('raw',data),('pruned',production)]:
            dest = work/side/category/variant/relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(content)
    for category, g in groups.items():
        for variant in ['raw','pruned']:
            output = subprocess.check_output(['cloc','--quiet','--json','--by-file','--skip-uniqueness',str(work/side/category/variant)],text=True)
            parsed = json.loads(output)
            g[variant+'Loc'] = parsed.get('SUM',{'code':0,'comment':0,'blank':0,'nFiles':0})
            prefix=work/side/category/variant
            by_path={record['path']:record for record in manifest}
            for filename, counts in parsed.items():
                if filename in ('header','SUM') or not isinstance(counts,dict) or 'code' not in counts: continue
                relative=Path(filename).relative_to(prefix)
                by_path[str(relative)][variant+'CodeLines']=counts['code']
    application_groups = ['application','platformGlue'] if side == 'java' else ['application']
    total = {k:sum(groups.get(g,{}).get(k,0) for g in application_groups)
             for k in ['files','bytes','sourceTokens','testPrunedTokens','explicitTestItemsRemoved']}
    total['rawCodeLines'] = sum(groups[g]['rawLoc']['code'] for g in application_groups if g in groups)
    total['testPrunedCodeLines'] = sum(groups[g]['prunedLoc']['code'] for g in application_groups if g in groups)
    modules={}
    for record in manifest:
        if record['category'] not in application_groups: continue
        parts=Path(record['path']).parts
        module=parts[0] if side=='java' else '/'.join(parts[:2])
        values=modules.setdefault(module,Counter())
        for field in ['bytes','sourceTokens','testPrunedTokens','rawCodeLines','prunedCodeLines']:
            values[field]+=record.get(field,0)
        values['files']+=1
    return {'revision':git_revision(root),'applicationTotal':total,'groups':groups,'modules':modules,'unprunedFiles':unpruned_files,'files':manifest}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('wordcraft',type=Path)
    p.add_argument('--cn1',type=Path,default=Path(__file__).resolve().parent.parent)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args()
    versions={name:importlib.metadata.version(name) for name in ['tiktoken','tree-sitter','tree-sitter-rust']}
    encoding=tiktoken.get_encoding('o200k_base')
    with tempfile.TemporaryDirectory(prefix='wordprocessor-metrics-') as temp:
        cn1=measure(args.cn1.resolve(),'java',encoding,Path(temp))
        rust=measure(args.wordcraft.resolve(),'rust',encoding,Path(temp))
    report={'schema':1,'tokenizer':'o200k_base','packages':versions,
            'clocVersion':subprocess.check_output(['cloc','--version'],text=True).strip(),
            'tokenDefinition':'Sum of separately tokenized UTF-8 file contents, including comments and whitespace; no filenames or chat framing; no minification.',
            'locDefinition':'cloc code lines excluding comments and blanks; --skip-uniqueness counts every selected source file.',
            'testPruning':'Rust AST removes explicit #[cfg(test)] and #[test] items, plus identified external test modules. Files rejected by the parser remain completely unpruned and are listed. This is a conservative test-pruned estimate, not resolution of all cfg/features or macro expansion.',
            'scope':'Java libraries + desktop host + CN1 lifecycle glue versus Rust crates/apps source. Framework/dependency source, assets, docs, generated outputs and build artifacts excluded. Tests, examples, benchmarks, build tooling reported separately.',
            'matchedFeatureScope':False,'languageSuperiorityEstablished':False,
            'cn1Java':cn1,'wordcraftRust':rust}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(report,indent=2)+'\n')
    for name, result in [('CN1/Java',cn1),('Wordcraft/Rust',rust)]:
        print(name,json.dumps(result['applicationTotal']))
    print('Scope is unmatched; these counts do not establish a language/framework advantage at parity.')

if __name__=='__main__':
    main()
