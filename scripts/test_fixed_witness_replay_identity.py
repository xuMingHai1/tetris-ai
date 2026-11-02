"""Small artificial byte/identity failures; never load the historical witness."""
import tempfile
import subprocess
import sys
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

import fixed_witness_replay_identity as identity


class IdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        for base in ('target/classes', 'target/test-classes'):
            (self.root / base).mkdir(parents=True)
        self.main = self.root / 'target/classes/A.class'
        self.main.write_bytes(b'artificial class bytes')

    def jar(self, name, entries):
        path = self.root / name
        with zipfile.ZipFile(path, 'w') as archive:
            for key, value in entries:
                archive.writestr(key, value)
        return path

    def test_bind_resources_and_detect_drift(self):
        resource = self.root / 'target/test-classes/artificial.txt'
        resource.write_bytes(b'one')
        first = identity.classpath_identity(self.root, [])
        self.assertIn('target/test-classes/artificial.txt', first['compiled_files'])
        identity.verify_identity(first, identity.classpath_identity(self.root, []))
        resource.write_bytes(b'two')
        with self.assertRaisesRegex(ValueError, 'drift'):
            identity.verify_identity(first, identity.classpath_identity(self.root, []))

    def test_reject_main_test_shadow(self):
        (self.root / 'target/test-classes/A.class').write_bytes(b'duplicate')
        with self.assertRaisesRegex(ValueError, 'shadow'):
            identity.classpath_identity(self.root, [])

    def test_reject_jar_shadow_and_duplicate_path(self):
        jar = self.jar('a.jar', [('A.class', b'other')])
        with self.assertRaisesRegex(ValueError, 'shadow'):
            identity.classpath_identity(self.root, [jar])
        jar = self.jar('b.jar', [('B.class', b'other')])
        with self.assertRaisesRegex(ValueError, 'duplicate classpath'):
            identity.classpath_identity(self.root, [jar, jar])

    def test_multi_release_same_jar_and_cross_jar_shadow(self):
        jar = self.jar('multi.jar', [('B.class', b'base'),
                       ('META-INF/versions/17/B.class', b'variant'),
                       ('module-info.class', b'module')])
        second = self.jar('second.jar', [('B.class', b'shadow'),
                          ('module-info.class', b'module')])
        identity.classpath_identity(self.root, [jar])
        with self.assertRaisesRegex(ValueError, 'shadow'):
            identity.classpath_identity(self.root, [jar, second])

    def test_implicit_classpath_and_duplicate_entries(self):
        for newline in ('\r\n', '\r', '\n'):
            for header in ('Class-Path', 'Class-' + newline + ' Path'):
                with self.subTest(newline=repr(newline), header=repr(header)):
                    jar = self.jar('implicit.jar', [('META-INF/MANIFEST.MF',
                                   'Manifest-Version: 1.0' + newline + header + ': hidden.jar' + newline)])
                    with self.assertRaisesRegex(ValueError, 'implicit'):
                        identity.classpath_identity(self.root, [jar])
        with self.assertWarns(UserWarning):
            jar = self.jar('duplicate.jar', [('B.class', b'one'), ('B.class', b'two')])
        with self.assertRaisesRegex(ValueError, 'duplicate jar'):
            identity.classpath_identity(self.root, [jar])

    def test_dependency_bytes_and_order_are_bound(self):
        a = self.jar('one.jar', [('B.class', b'one')])
        b = self.jar('two.jar', [('C.class', b'two')])
        first = identity.classpath_identity(self.root, [a, b])
        with self.assertRaisesRegex(ValueError, 'drift'):
            identity.verify_identity(first, identity.classpath_identity(self.root, [b, a]))
        self.jar('one.jar', [('B.class', b'changed')])
        with self.assertRaisesRegex(ValueError, 'drift'):
            identity.verify_identity(first, identity.classpath_identity(self.root, [a, b]))

    def test_link_rejection(self):
        link = self.root / 'linked'
        try:
            link.symlink_to(self.main)
        except OSError:
            # Windows commonly lacks symlink privilege; Linux CI exercises it.
            self.skipTest('symlink privilege unavailable')
        with self.assertRaisesRegex(ValueError, 'linked'):
            identity.sha256(link)

    def test_source_binds_tracked_bytes_and_rejects_dirty_hidden_ignored(self):
        source = self.root / 'pom.xml'
        source.write_bytes(b'artificial')

        def git(root, *args):
            if args == ('rev-parse', '--show-toplevel'):
                return str(self.root)
            if args == ('rev-parse', 'HEAD'):
                return 'a' * 40
            if args == ('ls-files', '-v', '-z'):
                return 'H pom.xml\0'
            return ''

        with patch.object(identity, '_git', side_effect=git):
            first = identity.source_identity(self.root)
            source.write_bytes(b'changed')
            self.assertNotEqual(first, identity.source_identity(self.root))
        for kind, bad in [('status', ' M pom.xml'), ('ls-files', 'h pom.xml\0')]:
            def rejected(root, *args):
                return bad if args[0] == kind else git(root, *args)
            with patch.object(identity, '_git', side_effect=rejected), self.assertRaises(ValueError):
                identity.source_identity(self.root)
        def ignored(root, *args):
            return 'src/extra.java' if '--ignored' in args else git(root, *args)
        with patch.object(identity, '_git', side_effect=ignored), self.assertRaisesRegex(ValueError, 'ignored'):
            identity.source_identity(self.root)

    @unittest.skipUnless(sys.platform == 'linux', 'real Git inventory covered on Linux')
    def test_real_clean_checkout_and_hidden_index(self):
        def git(*args):
            subprocess.run(['git', *args], cwd=self.root, check=True,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        git('init')
        (self.root / '.gitignore').write_text('target/\n', encoding='utf-8')
        (self.root / 'pom.xml').write_bytes(b'artificial source')
        git('add', '.gitignore', 'pom.xml')
        git('-c', 'user.name=Artificial Test', '-c', 'user.email=test@example.invalid',
            '-c', 'commit.gpgsign=false', 'commit', '-m', 'artificial fixture')
        first = identity.source_identity(self.root)
        self.assertEqual(len(first['source_files']), 2)
        git('update-index', '--assume-unchanged', 'pom.xml')
        with self.assertRaisesRegex(ValueError, 'hidden'):
            identity.source_identity(self.root)
        git('update-index', '--no-assume-unchanged', 'pom.xml')
        (self.root / 'pom.xml').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'dirty'):
            identity.source_identity(self.root)


if __name__ == '__main__':
    unittest.main()
