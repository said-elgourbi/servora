#!/usr/bin/env python3
"""Run the device-free push primitives against compiled debug classes, without Gradle tests.

Requires a current assembleDebug and cached Kotlin/JUnit dependencies. Does not test Android APIs,
FCM connectivity, Compose, registration or ViewModels. Compiler heap: 384 MB; test heap: 128 MB.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

android = Path(__file__).resolve().parents[1]
versions = dict(re.findall(r'^(\w+) = "([^"]+)"$', (android / 'gradle/libs.versions.toml').read_text(), re.M))
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
main = android / 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes'
if not (main / 'com/servora/android/data/push/AssignmentPushEligibilityKt.class').exists():
    raise SystemExit('Build the current debug app first: see docs/development/firebase.md (resource-capped build).')
artifacts = [
    ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', versions['kotlin']),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', versions['kotlin']),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime', versions['kotlin']),
    ('org.jetbrains.kotlin', 'kotlin-reflect', None),
    ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', versions['kotlin']),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', versions['coroutines']),
    ('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', versions['kotlinxSerialization']),
    ('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', versions['kotlinxSerialization']),
    ('org.jetbrains', 'annotations', None),
    ('javax.inject', 'javax.inject', None),
    ('junit', 'junit', versions['junit']),
    ('org.hamcrest', 'hamcrest-core', None),
]
jars = []
for group, artifact, version in artifacts:
    base = cache / group / artifact
    if version:
        base = base / version
    candidates = sorted(base.glob('**/*.jar'))
    if not candidates:
        raise SystemExit(f'Missing cached {group}:{artifact}; use the normal Android dependency setup first.')
    jars.append(str(candidates[-1]))
classpath = os.pathsep.join([str(main)] + jars)
names = ['AssignmentPushMessageTest', 'AssignmentPushEligibilityTest', 'PushReceiptLedgerTest']
sources = [str(android / f'app/src/test/java/com/servora/android/data/push/{name}.kt') for name in names]
with tempfile.TemporaryDirectory(prefix='servora-push-jvm-') as output:
    subprocess.run([
        'java', '-Xmx384m', '-cp', os.pathsep.join(jars), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-Xfriend-paths=' + str(main),
        '-classpath', classpath, '-d', output, *sources,
    ], check=True)
    subprocess.run([
        'java', '-Xmx128m', '-cp', output + os.pathsep + classpath, 'org.junit.runner.JUnitCore',
        *['com.servora.android.data.push.' + name for name in names],
    ], check=True)
