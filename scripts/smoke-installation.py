#!/usr/bin/env python3
"""Exercise actual native installation/update/rollback using two already signed local bundles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--first-bundle', type=Path, required=True)
    parser.add_argument('--second-bundle', type=Path, required=True)
    args = parser.parse_args()
    binary = str(args.binary.resolve())
    target = ('windows-x64' if os.name == 'nt' else
              ('macos-' if platform.system() == 'Darwin' else 'linux-') +
              ('arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x64'))
    first, second = args.first_bundle.resolve(), args.second_bundle.resolve()
    manifests = [json.loads((directory / 'manifest.json').read_bytes()) for directory in (first, second)]
    digests = [hashlib.sha256((directory / 'manifest.json').read_bytes()).hexdigest() for directory in (first, second)]
    assert digests[0] != digests[1], 'Use distinct signed manifests to exercise selection and rollback'
    inputs = {path: hashlib.sha256(path.read_bytes()).hexdigest() for directory, manifest in zip((first, second), manifests)
              for path in (directory / 'manifest.json', directory / 'manifest.sig', directory / manifest['assets'][target]['fileName'])}

    with tempfile.TemporaryDirectory(prefix='controlresell-installation-') as directory:
        root = Path(directory) / "application é space's"
        install_root = root / 'installation'
        config_root = root / 'configuration'
        env = dict(os.environ, XDG_CONFIG_HOME=str(config_root), CONTROLRESELL_DISABLE_AUTOUPDATE='1')
        root.mkdir()
        launcher = install_root / 'bin' / ('cr.exe' if os.name == 'nt' else 'cr')

        def run(executable, arguments, error=None, input_text=None):
            io = {'stdin': subprocess.DEVNULL} if input_text is None else {'input': input_text}
            result = subprocess.run([str(executable), *arguments], env=env, **io,
                                    capture_output=True, text=True, timeout=45)
            output = result.stdout + result.stderr
            if error:
                assert result.returncode != 0 and error in output, f'Expected {error}; exit={result.returncode}: {output}'
            else:
                assert result.returncode == 0, f'Native installation operation failed: {output}'
            return output

        def state(): return json.loads((install_root / 'current.json').read_bytes())
        def selected(index):
            assert run(launcher, ['version']).strip() == 'ControlResell CLI ' + manifests[index]['version']
            assert state()['current']['manifestSha256'] == digests[index]
            assert not (install_root / 'transaction.json').exists(), 'A completed operation left a pending journal'

        run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        original_launcher = hashlib.sha256(launcher.read_bytes()).hexdigest()
        before_settings = state()
        def notifications(mode):
            return run(binary, ['update', '--root', str(install_root), '--notifications', mode])
        assert 'notifications: on.' in notifications('status')
        notifications('off')
        assert 'notifications: off.' in notifications('status')
        notifications('on')
        assert 'notifications: on.' in notifications('status')
        # OS managers do not inherit the invoking shell's environment; persist the CI opt-out.
        notifications('off')
        assert state() == before_settings
        run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        run(launcher, ['update', '--bundle', str(second)])
        selected(1)
        assert state()['previous']['manifestSha256'] == digests[0]
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher, 'The running launcher was replaced'
        run(launcher, ['update', '--rollback'])
        selected(0)
        assert state()['previous']['manifestSha256'] == digests[1]
        for digest in digests: assert (install_root / 'versions' / digest).is_dir(), 'A previous payload was deleted'

        # Reconstruct the durable state left by an interrupted selection before its startup commit.
        before = state()
        after = dict(before, current=before['previous'], previous=before['current'])
        (install_root / 'transaction.json').write_text(json.dumps({'schemaVersion': 1, 'before': before, 'after': after}), encoding='utf-8')
        (install_root / 'current.json').write_text(json.dumps(after), encoding='utf-8')
        (install_root / 'transaction.json').chmod(0o600)
        selected(0)
        assert state() == before, 'Interrupted transaction did not restore the exact previous selection'

        corrupt = root / 'corrupt bundle'
        corrupt.mkdir()
        for name in ('manifest.json', 'manifest.sig'): shutil.copyfile(first / name, corrupt / name)
        (corrupt / manifests[0]['assets'][target]['fileName']).write_bytes(b'UNTRUSTED_EXECUTABLE_MUST_NEVER_RUN')
        rejected_root = root / 'rejected installation'
        run(binary, ['install', '--bundle', str(corrupt), '--root', str(rejected_root)], error='release_binary_invalid')
        assert not (rejected_root / 'current.json').exists()
        selected(0)
        assert state() == before

        foreign = root / 'unrelated directory'
        foreign.mkdir()
        (foreign / 'keep.txt').write_bytes(b'preserve this user file')
        run(binary, ['install', '--bundle', str(first), '--root', str(foreign)], error='release_directory_not_owned')
        assert (foreign / 'keep.txt').read_bytes() == b'preserve this user file'
        assert sorted(path.name for path in foreign.iterdir()) == ['keep.txt']
        assert not config_root.exists(), 'Installation without login created a configuration'
        if os.name != 'nt':
            assert install_root.stat().st_mode & 0o077 == 0
            assert launcher.stat().st_mode & 0o777 == 0o700
            record = install_root / 'current.json'
            record.chmod(0o644)
            try: run(launcher, ['version'], error='release_private_path_invalid')
            finally: record.chmod(0o600)
            selected(0)
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher
        for path, digest in inputs.items(): assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'Source bundle changed'
    print('PASS: native signed installation, immutable launcher, update, offline rollback, interrupted-selection recovery, integrity refusal, private Unicode paths and preserved input/configuration.')


if __name__ == '__main__':
    main()
