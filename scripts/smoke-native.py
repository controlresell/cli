#!/usr/bin/env python3
"""Run the real native CLI against a local fake of the public API; never contact ControlResell.

Usage: python3 scripts/smoke-native.py --binary path/to/cr.kexe [--expected-version 0.1.0]
Only subprocesses created by this script are terminated. Uses an isolated XDG directory.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_ready(url, process):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Owned test process exited before readiness.')
        try:
            urllib.request.urlopen(url, timeout=1)
            return
        except urllib.error.HTTPError:
            return
        except (OSError, TimeoutError):
            pass
        time.sleep(.1)
    raise RuntimeError('Owned test process did not become ready.')


class Cli:
    def __init__(self, binary, env, key):
        self.binary, self.env, self.key = binary, env, key

    def __call__(self, *arguments, stdin='', code=0, env=None):
        result = subprocess.run([self.binary, *arguments], input=stdin, text=True, capture_output=True,
                                env={**self.env, **(env or {})}, timeout=60)
        assert result.returncode == code, f'{arguments}: exit {result.returncode}, expected {code}\n{result.stderr}'
        assert self.key not in result.stdout and self.key not in result.stderr, f'{arguments} printed the API key.'
        return result


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--binary', type=Path, required=True)
    args.add_argument('--expected-version')
    options = args.parse_args()
    api_port = port()
    key = 'cr_test_' + secrets.token_hex(16)
    processes = []
    with tempfile.TemporaryDirectory(prefix='controlresell-smoke-') as directory:
        env = {name: value for name, value in os.environ.items() if name != 'CONTROLRESELL_API_KEY'}
        env['XDG_CONFIG_HOME'] = directory
        origin = f'http://127.0.0.1:{api_port}'
        log = Path(directory) / 'requests.jsonl'
        cr = Cli(str(options.binary.resolve()), env, key)
        try:
            server = subprocess.Popen([sys.executable, str(Path(__file__).with_name('fake-api.py')), '--port', str(api_port),
                                       '--key', key, '--log', str(log)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            processes.append(server)
            wait_ready(origin + '/api/public/v1/me', server)
            if options.expected_version:
                assert cr('version').stdout.strip() == 'ControlResell CLI ' + options.expected_version

            # Credentials: none yet, a wrong key is refused and never saved, the right one is saved privately.
            assert 'Not signed in' in cr('whoami', code=3).stderr
            cr('login', '--api-url', origin, stdin='not-a-key\n', code=3)
            refused = cr('login', '--api-url', origin, stdin='cr_test_wrong\n', code=3)
            assert 'error: invalid_token' in refused.stderr and '(request req_smoke)' in refused.stderr
            config_path = Path(directory) / 'controlresell/config.json'
            assert not config_path.exists()
            assert 'Signed in as smoke-seller.' in cr('login', '--api-url', origin, stdin=key + '\n').stdout
            config = json.loads(config_path.read_text())
            assert config == {'apiUrl': origin, 'apiKey': key}
            if os.name == 'posix':
                assert stat.S_IMODE(config_path.stat().st_mode) == 0o600
                assert stat.S_IMODE(config_path.parent.stat().st_mode) == 0o700

            # The key travels only as a bearer token, never in a URL.
            for request in (json.loads(line) for line in log.read_text().splitlines()):
                assert key not in request['path'] and key not in json.dumps(request['query'])
            assert 'smoke-seller' in cr('whoami').stdout
            assert json.loads(cr('whoami', '--json').stdout) == {'id': 42, 'username': 'smoke-seller', 'currency': 'EUR'}
            assert cr('whoami', '--json').stderr == ''

            # The environment key takes precedence over the saved one.
            overridden = cr('whoami', env={'CONTROLRESELL_API_KEY': 'cr_test_other'}, code=3)
            assert 'error: invalid_token' in overridden.stderr
            cr('whoami', '--bogus', code=2)

            # Reads: tables, JSON pages as the API answers them, every page with --all, filters and exit codes.
            listed = cr('items', 'list', '--limit', '2')
            assert 'Item 1' in listed.stdout and 'Item 2' in listed.stdout and 'Item 3' not in listed.stdout
            assert 'Next page: run the same command with --cursor 2' in listed.stderr
            merged = json.loads(cr('items', 'list', '--all', '--limit', '2', '--json').stdout)
            assert [item['id'] for item in merged['data']] == [1, 2, 3, 4, 5] and merged['nextCursor'] is None
            assert [item['id'] for item in json.loads(cr('items', 'list', '--sold', 'true', '--json').stdout)['data']] == [2, 4]
            assert 'Item 3' in cr('items', 'get', '3').stdout
            assert 'example.com/items/9001' in cr('items', 'listings', '1').stdout
            assert 'Account 2' in cr('accounts', 'list', '--all').stdout
            assert json.loads(cr('accounts', 'get', 'acc_1', '--json').stdout)['name'] == 'Account 1'
            assert 'buyer1' in cr('conversations', 'list', '--unread', 'true').stdout
            assert 'Hello 3' in cr('conversations', 'messages', 'conv_1', '--all').stdout
            assert 'buyer2' in cr('conversations', 'get', 'conv_2').stdout
            assert cr('items', 'get', '999', code=4).stderr.startswith('error: item_not_found: No such item. (request req_smoke)')
            assert 'error: plan_required' in cr('items', 'get', '402', code=5).stderr
            assert 'Retry after 7 seconds.' in cr('items', 'get', '429', code=6).stderr
            resumed = json.loads(cr('items', 'list', '--all', '--cursor', '2', '--json').stdout)
            assert [item['id'] for item in resumed['data']] == [3, 4, 5]
            cr('items', 'list', '--limit', '0', code=2)

            cr('logout')
            assert not config_path.exists()
            cr('whoami', code=3)
            print('Native login on stdin, private configuration, whoami, reads, pagination, JSON output, exit codes and key redaction verified.')
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()  # Exact child owned by this test, never an unrelated process.
                        process.wait(timeout=5)


if __name__ == '__main__':
    main()
