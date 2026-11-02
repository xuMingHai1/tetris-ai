"""Artificial toolchains only; never build or read the historical witness."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fixed_witness_replay_environment import (
    WRAPPER_PROPERTIES, controlled_environment, jvm_directory_options, toolchain_identity,
)
from fixed_witness_replay_identity import verify_identity


class EnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name).resolve()
        self.root, self.jdk, self.home, self.tmp = [self.base / name
                                                 for name in ('source', 'jdk', 'home', 'tmp')]
        for path in (self.root, self.jdk, self.home, self.tmp):
            path.mkdir()
        self.props = self.root / '.mvn/wrapper/maven-wrapper.properties'
        self.props.parent.mkdir(parents=True)
        self.props.write_text(''.join(k + '=' + v + '\n' for k, v in WRAPPER_PROPERTIES.items()))
        (self.root / 'mvnw').write_bytes(b'artificial wrapper')
        for name in ('bin/java', 'bin/javac', 'lib/modules', 'lib/server/libjvm.so'):
            path = self.jdk / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'artificial ' + name.encode())
        (self.jdk / 'release').write_text('JAVA_VERSION="27.0.1"\n')

    def identity(self):
        return toolchain_identity(self.root, self.jdk)

    @unittest.skipUnless(os.name == 'posix', 'Linux environment paths required')
    def test_ambient_injection_and_credentials_are_never_inherited(self):
        poison = {name: 'sentinel-private-value' for name in (
            'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS',
            'MAVEN_ARGS', 'CLASSPATH', 'LD_PRELOAD', 'LD_LIBRARY_PATH', 'TYPESAFE_API_KEY',
            'GITHUB_TOKEN', 'HOME', 'PATH', 'BASH_ENV', 'ENV', 'MAVEN_USER_HOME')}
        with patch.dict(os.environ, poison, clear=True):
            env = controlled_environment(self.jdk, self.home, self.tmp)
        self.assertEqual({'JAVA_HOME', 'HOME', 'TMPDIR', 'PATH', 'MAVEN_OPTS', 'LANG', 'LC_ALL', 'TZ'}, set(env))
        self.assertNotIn('sentinel-private-value', json.dumps(env))
        self.assertEqual(str(self.jdk / 'bin') + ':/usr/bin', env['PATH'])
        self.assertEqual(' '.join(jvm_directory_options(self.home, self.tmp)), env['MAVEN_OPTS'])

    @unittest.skipUnless(os.name == 'posix', 'Linux environment paths required')
    def test_existing_home_configuration_and_tmp_are_rejected(self):
        for directory in (self.home, self.tmp):
            with self.subTest(directory=directory.name):
                extra = directory / 'unexpected'
                extra.write_text('private configuration')
                with self.assertRaisesRegex(ValueError, 'fresh empty'):
                    controlled_environment(self.jdk, self.home, self.tmp)
                extra.unlink()
        spaced = self.base / 'space dir'
        spaced.mkdir()
        with self.assertRaisesRegex(ValueError, 'whitespace-free'):
            controlled_environment(self.jdk, spaced, self.tmp)

    @unittest.skipUnless(os.name == 'posix', 'Linux environment paths required')
    def test_alias_and_nested_environment_directories_are_rejected(self):
        for directory in (self.home, self.jdk):
            with self.assertRaisesRegex(ValueError, 'disjoint'):
                controlled_environment(self.jdk, self.home, directory)
        nested = self.home / 'nested'
        nested.mkdir()
        with self.assertRaisesRegex(ValueError, 'disjoint'):
            controlled_environment(self.jdk, self.home, nested)

    def test_complete_identity_detects_native_and_wrapper_byte_drift(self):
        expected = self.identity()
        self.assertEqual(5, len(expected['jdk_files']))
        for file in (self.jdk / 'lib/server/libjvm.so', self.root / 'mvnw'):
            original = file.read_bytes()
            file.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'drift'):
                verify_identity(expected, self.identity())
            file.write_bytes(original)
        verify_identity(expected, self.identity())

    def test_release_wrong_major_duplicate_or_malformed_is_rejected(self):
        for text in ('JAVA_VERSION="26"\n', 'JAVA_VERSION="270"\n',
                     'JAVA_VERSION="27"\nJAVA_VERSION="27"\n', 'JAVA_VERSION=27\n',
                     'JAVA_VERSION="27vendor"\n', 'IMPLEMENTOR="x"\n'):
            with self.subTest(text=text):
                (self.jdk / 'release').write_text(text)
                with self.assertRaises(ValueError):
                    self.identity()

    def test_incomplete_jdk_is_rejected(self):
        (self.jdk / 'bin/javac').unlink()
        with self.assertRaises(FileNotFoundError):
            self.identity()

    def test_wrapper_property_overrides_and_unpinned_urls_are_rejected(self):
        original = self.props.read_text()
        for text in (original + 'distributionUrl=other\n', original + 'extra=value\n',
                     original.replace('3.9.16-bin.zip', '3.9.17-bin.zip'),
                     original.replace('5af3b743', '00000000'), original + 'key=\\value\n'):
            with self.subTest(text=text):
                self.props.write_text(text)
                with self.assertRaises(ValueError):
                    self.identity()

    def test_added_jdk_input_is_visible_to_retained_identity(self):
        expected = self.identity()
        (self.jdk / 'lib/extra.so').write_bytes(b'extra native input')
        with self.assertRaisesRegex(ValueError, 'drift'):
            verify_identity(expected, self.identity())

    @unittest.skipUnless(os.name == 'posix', 'Linux symlink semantics required')
    def test_linked_jdk_or_environment_inputs_are_rejected(self):
        outside = self.base / 'external'
        outside.write_bytes(b'external')
        (self.jdk / 'lib/extra.so').symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'external or directory'):
            self.identity()
        alias = self.base / 'alias'
        alias.symlink_to(self.home, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'linked/noncanonical'):
            controlled_environment(self.jdk, alias, self.tmp)

    @unittest.skipUnless(os.name == 'posix', 'Linux symlink semantics required')
    def test_internal_jdk_file_links_are_bound_but_directory_links_rejected(self):
        notice = self.jdk / 'notice'
        notice.write_bytes(b'legal')
        link = self.jdk / 'legal'
        link.symlink_to('notice')
        expected = self.identity()
        self.assertEqual({'legal': 'notice'}, expected['jdk_links'])
        self.assertEqual(expected['jdk_files']['notice'], expected['jdk_files']['legal'])
        notice.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'drift'):
            verify_identity(expected, self.identity())
        (self.jdk / 'alias').symlink_to('bin', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'external or directory'):
            self.identity()


if __name__ == '__main__':
    unittest.main()
