"""工作目录的测试包编号；成功产出 APK 后才消耗一个 r/b/c 编号。"""
import json
import re


def prepare_test_version(app):
    manifest = app / 'src/main/AndroidManifest.xml'
    text = manifest.read_text(encoding='utf-8')
    current = re.search(r'android:versionName="([^"]+)"', text).group(1)
    match = re.fullmatch(r'(\d+\.\d+)\.([rbc])(\d+)', current)
    if not match:
        if re.fullmatch(r'\d+\.\d+(?:\.\d+)?', current):
            return current, None, None
        raise ValueError('版本须为正式版本号或 主版本.次版本.r/b/c编号。')
    base, channel, current_revision = match.group(1), match.group(2), int(match.group(3))
    state_path = app / 'tools/test-version.json'
    state = json.loads(state_path.read_text(encoding='utf-8')) if state_path.exists() else {}
    revisions = state.get('revisions')
    if not isinstance(revisions, dict):
        revisions = {}
        if state.get('base') and 'lastSuccessfulRevision' in state:
            revisions[f"{state['base']}.r"] = int(state['lastSuccessfulRevision'])
    key = f'{base}.{channel}'
    revision = max(current_revision, int(revisions.get(key, 0)) + 1)
    version = f'{base}.{channel}{revision}'
    code = int(re.search(r'android:versionCode="(\d+)"', text).group(1))
    next_code = code + revision - current_revision
    text = text.replace(f'android:versionName="{current}"', f'android:versionName="{version}"')
    text = text.replace(f'android:versionCode="{code}"', f'android:versionCode="{next_code}"')
    manifest.write_text(text, encoding='utf-8')
    # Keep the native fallback, daemon version and WebView cache keys in step.
    for root in ('src', 'daemon'):
        for path in (app / root).rglob('*'):
            if not path.is_file() or path.suffix not in ('.java', '.html', '.js'):
                continue
            source = path.read_text(encoding='utf-8')
            updated = source.replace(f'acol-{current}', f'acol-{version}')
            updated = updated.replace(f'App {current} Android=', f'App {version} Android=')
            updated = updated.replace(f'"version","{current}"', f'"version","{version}"')
            updated = updated.replace(f'"{current} · AstriaFR"', f'"{version} · AstriaFR"')
            if updated != source:
                path.write_text(updated, encoding='utf-8')
    return version, state_path, {'revisions': {**revisions, key: revision}}


def complete_test_version(state_path, state):
    if state_path is None:
        return
    temporary = state_path.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(state_path)
