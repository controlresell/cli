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
        home = root / 'home'
        # Installation links the command under HOME: never let this smoke touch the real one.
        env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(config_root), CONTROLRESELL_DISABLE_AUTOUPDATE='1')
        env['PATH'] = os.pathsep.join(entry for entry in env['PATH'].split(os.pathsep) if Path(entry) != home / '.local' / 'bin')
        root.mkdir()
        home.mkdir()
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

        if os.name == 'nt':
            import winreg
            def user_path():
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as key:
                    try: return winreg.QueryValueEx(key, 'Path')
                    except FileNotFoundError: return None
            def launcher_directories(): return [entry.rstrip('\\').lower() for entry in (user_path() or ('',))[0].split(';')]
            original_user_path = user_path()
            def restore_user_path():
                # The Windows install edits the real HKCU PATH of the CI user: put it back exactly.
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment', 0, winreg.KEY_SET_VALUE) as key:
                    if original_user_path is None: winreg.DeleteValue(key, 'Path')
                    else: winreg.SetValueEx(key, 'Path', 0, original_user_path[1], original_user_path[0])
            import atexit; atexit.register(restore_user_path)
        installed = run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        link_directory = home / '.local' / 'bin'
        command_directory = launcher.parent if os.name == 'nt' else link_directory
        def by_name():
            # Resolved through PATH like an interactive shell would; never through the launcher's full path.
            found = shutil.which('cr', path=str(command_directory))
            assert found, 'cr is not found by name'
            result = subprocess.run([found, 'version'], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            assert result.returncode == 0, result.stdout + result.stderr
            return result.stdout.strip()
        if os.name == 'nt':
            assert str(launcher.parent).lower() in launcher_directories(), 'Install did not add the launcher directory to the user PATH'
            assert launcher_directories().count(str(launcher.parent).lower()) == 1
        else:
            link = link_directory / 'cr'
            assert link.is_symlink() and os.path.realpath(link) == os.path.realpath(launcher), 'The command must link to the stable launcher'
            assert 'export PATH="$HOME/.local/bin:$PATH"' in installed, 'Missing the exact PATH line: ' + installed
        assert by_name() == 'ControlResell CLI ' + manifests[0]['version']
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
        if os.name != 'nt':
            assert os.path.realpath(link) == os.path.realpath(launcher) and len(list(link_directory.iterdir())) == 1, 'Re-running install changed the command link'
        run(launcher, ['update', '--bundle', str(second)])
        selected(1)
        assert by_name() == 'ControlResell CLI ' + manifests[1]['version'], 'The command did not follow the update'
        assert state()['previous']['manifestSha256'] == digests[0]
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher, 'The running launcher was replaced'
        run(launcher, ['update', '--rollback'])
        selected(0)
        assert by_name() == 'ControlResell CLI ' + manifests[0]['version'], 'The command did not follow the rollback'
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

        if os.name != 'nt':
            # A command name owned by something else is never replaced, and the refusal says why.
            other_home = root / 'other home'
            (other_home / '.local' / 'bin').mkdir(parents=True)
            occupied = other_home / '.local' / 'bin' / 'cr'
            occupied.write_text('not ours')
            result = subprocess.run([str(binary), 'install', '--bundle', str(first), '--root', str(root / 'other installation')],
                                    env=dict(env, HOME=str(other_home)), stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            assert result.returncode != 0 and 'already exists and was not created by ControlResell' in result.stderr + result.stdout, result.stdout + result.stderr
            assert occupied.read_text() == 'not ours' and not occupied.is_symlink()
            result = subprocess.run([str(binary), 'install', '--no-path', '--bundle', str(first), '--root', str(root / 'other installation')],
                                    env=dict(env, HOME=str(other_home)), stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            assert result.returncode == 0 and occupied.read_text() == 'not ours', result.stdout + result.stderr

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
