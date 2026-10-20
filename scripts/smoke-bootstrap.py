#!/usr/bin/env python3
"""Run the generated bootstrap with a publisher-signed native bundle on its actual target host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import subprocess
import tempfile


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle.resolve(strict=True)
    target = ('windows-x64' if os.name == 'nt' else
              ('macos-' if platform.system() == 'Darwin' else 'linux-') +
              ('arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x64'))
    manifest = json.loads((bundle / 'manifest.json').read_bytes())
    asset = manifest['assets'][target]
    script = bundle / ('install.ps1' if os.name == 'nt' else 'install.sh')
    assert script.is_file(), 'Use the generated installer from the same signed bundle'
    original = {path: digest(path) for path in (script, bundle / 'manifest.json', bundle / 'manifest.sig', bundle / asset['fileName'])}
    shells = ([executable for executable in (shutil.which('powershell.exe'), shutil.which('pwsh')) if executable]
              if os.name == 'nt' else [shutil.which('sh')])
    assert shells and all(shells), 'Install the platform shell before bootstrap acceptance'

    with tempfile.TemporaryDirectory(prefix='controlresell-bootstrap-native-') as directory:
        workspace = Path(directory)
        key = 'cr_test_' + secrets.token_hex(16)
        if True:
            for index, shell in enumerate(shells):
                root = workspace / ("application é space's " + str(index)); root.mkdir()
                install_root = root / 'installation'
                home = root / 'home'; home.mkdir()
                temporary = root / 'temporary'; temporary.mkdir()
                config_root = root / 'configuration'
                env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(config_root),
                           TMPDIR=str(temporary), TEMP=str(temporary), TMP=str(temporary),
                           CONTROLRESELL_INSTALL_ROOT=str(install_root), CONTROLRESELL_INSTALL_BUNDLE=str(bundle),
                           CONTROLRESELL_DISABLE_AUTOUPDATE='1')
                launcher = install_root / 'bin' / ('cr.exe' if os.name == 'nt' else 'cr')
                invoke = [shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(script)] if os.name == 'nt' else [shell, str(script)]

                def run(command, environment=env, success=True):
                    result = subprocess.run(command, env=environment, stdin=subprocess.DEVNULL, capture_output=True, timeout=60)
                    if success:
                        assert result.returncode == 0, (result.stdout + result.stderr).decode(errors='replace')
                    else: assert result.returncode != 0, 'Bootstrap unexpectedly accepted modified release bytes'
                    return result

                if os.name == 'nt':
                    # Exercise the same HTTP helper on both .NET Framework /
                    # Windows PowerShell and modern PowerShell, without network.
                    run([shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
                         str(Path(__file__).resolve().parent / 'fixtures/bootstrap-http.ps1'), '-Template', str(script)])

                if os.name == 'nt':
                    import atexit, winreg
                    def user_path():
                        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as key:
                            try: return winreg.QueryValueEx(key, 'Path')
                            except FileNotFoundError: return None
                    original_user_path = user_path()
                    def restore_user_path():
                        # The Windows install edits the real HKCU PATH of the CI user: put it back exactly.
                        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment', 0, winreg.KEY_SET_VALUE) as key:
                            if original_user_path is None: winreg.DeleteValue(key, 'Path')
                            else: winreg.SetValueEx(key, 'Path', 0, original_user_path[1], original_user_path[0])
                    atexit.register(restore_user_path)

                # A clean install needs no account, API key or network access to the API.
                run(invoke)
                # `cr` must work by name right after the install, in a PATH that has the command directory.
                command_directory = install_root / 'bin' if os.name == 'nt' else home / '.local' / 'bin'
                if os.name == 'nt':
                    assert str(command_directory).lower() in [entry.rstrip('\\').lower() for entry in user_path()[0].split(';')]
                else:
                    assert (command_directory / 'cr').is_symlink() and os.path.realpath(command_directory / 'cr') == os.path.realpath(launcher)
                by_name = shutil.which('cr', path=str(command_directory))
                assert by_name and run([by_name, 'version']).stdout.decode().strip() == 'ControlResell CLI ' + manifest['version']
                assert run([str(launcher), 'version']).stdout.decode().strip() == 'ControlResell CLI ' + manifest['version']
                state = json.loads((install_root / 'current.json').read_bytes())
                assert state['current']['manifestSha256'] == digest(bundle / 'manifest.json')
                assert not config_root.exists(), 'Fresh bootstrap created a login'
                assert not list(temporary.glob('controlresell-install*')), 'Bootstrap left downloaded temporary files'
                launcher_hash = digest(launcher)

                # Re-running uses the same installation while preserving a saved login.
                config = config_root / 'controlresell/config.json'; config.parent.mkdir(parents=True)
                config.write_text(json.dumps(dict(apiUrl='http://127.0.0.1:9', apiKey=key)))
                config.chmod(0o600)
                preserved = [config]
                before = {path: path.read_bytes() for path in preserved}
                result = run(invoke)
                assert key.encode() not in result.stdout + result.stderr, 'Bootstrap printed the saved API key'
                assert {path: path.read_bytes() for path in preserved} == before
                assert digest(launcher) == launcher_hash, 'Idempotent bootstrap replaced the stable launcher'
                if os.name != 'nt': assert os.path.realpath(command_directory / 'cr') == os.path.realpath(launcher) and len(list(command_directory.iterdir())) == 1
                assert run([str(launcher), 'version']).stdout.decode().strip() == 'ControlResell CLI ' + manifest['version']

                corrupt = root / 'corrupt'; corrupt.mkdir()
                for filename in ('manifest.json', 'manifest.sig', asset['fileName']): shutil.copyfile(bundle / filename, corrupt / filename)
                binary = corrupt / asset['fileName']
                with binary.open('r+b') as stream:
                    byte = stream.read(1); stream.seek(0); stream.write(bytes([byte[0] ^ 1]))
                rejected = root / 'rejected'
                run(invoke, dict(env, CONTROLRESELL_INSTALL_BUNDLE=str(corrupt), CONTROLRESELL_INSTALL_ROOT=str(rejected)), success=False)
                assert not rejected.exists(), 'Corrupted bootstrap executed the native installer'
                assert not list(temporary.glob('controlresell-install*')), 'Failed bootstrap left temporary files'
                assert {path: path.read_bytes() for path in preserved} == before
        assert {path: digest(path) for path in original} == original, 'Bootstrap modified its signed source bundle'
    print('PASS: generated bootstrap with actual native install/idempotence, version selection, login preservation, corrupt-byte refusal and cleanup on ' + target + '.')


if __name__ == '__main__': main()
