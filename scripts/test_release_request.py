import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_request', Path(__file__).with_name('release-request.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseRequestTest(unittest.TestCase):
    def test_publication_requires_explicit_full_stable_main_request(self):
        good = dict(publish_release=True, full_validation=True, validation_version='1.0.0')
        self.assertTrue(release.validate_request('workflow_dispatch', 'refs/heads/main', good, '1.0.0'))
        self.assertTrue(release.validate_request('workflow_dispatch', 'refs/heads/main', dict(good, publish_release='true', full_validation='true'), '1.0.0'))
        for ref, inputs, version in [
            ('refs/heads/feature', good, '1.0.0'),
            ('refs/heads/main', dict(good, full_validation=False), '1.0.0'),
            ('refs/heads/main', dict(good, validation_version=''), '1.0.0'),
            *[('refs/heads/main', dict(good, validation_version=v), v) for v in ['v1.0.0', '1.0.0-SNAPSHOT', '1.0.0-alpha.1', '01.0.0', '1.0.0/escape']],
        ]:
            with self.subTest(ref=ref, version=version), self.assertRaises(ValueError):
                release.validate_request('workflow_dispatch', ref, inputs, version)

    def test_ordinary_ci_and_acceptance_do_not_publish(self):
        self.assertFalse(release.validate_request('push', 'refs/heads/main', {}, '1.0.0-SNAPSHOT'))
        self.assertFalse(release.validate_request('pull_request', 'refs/pull/1', dict(publish_release=True), '1.0.0'))
        self.assertFalse(release.validate_request('workflow_dispatch', 'refs/heads/main', dict(full_validation=True), '1.0.0'))

    def test_release_identity_binds_exact_tag_version_and_source(self):
        good = dict(version='1.0.0', tag='1.0.0', sourceCommit='a' * 40)
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'manifest.json'
            path.write_text(json.dumps(good))
            release.validate_manifest(path, '1.0.0', 'a' * 40)
            for change in [dict(tag='v1.0.0'), dict(version='1.0.1'), dict(sourceCommit='b' * 40)]:
                path.write_text(json.dumps(dict(good, **change)))
                with self.subTest(change=change), self.assertRaises(ValueError):
                    release.validate_manifest(path, '1.0.0', 'a' * 40)
