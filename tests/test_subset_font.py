"""Offline end-to-end regressions; no font download or fontTools installation needed."""
import contextlib
import importlib.util
import io
from pathlib import Path
import sys
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("subset_font", ROOT / "scripts/subset_font.py")
subset_font = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subset_font)

FONT = '@font-face{font-family:"Nebulove";src:url("old.woff2");}'
NEW_SOURCE = 'data:font/woff2;charset=utf-8;base64,'


class SubsetFontTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.index = Path(self.directory.name) / "index.html"
        self.texts = []
        texts = self.texts

        class Font:
            def __init__(self, *args, **kwargs):
                self.head = SimpleNamespace(created=1234567890, modified=0)

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def __getitem__(self, key):
                return self.head

            def save(self, filename):
                Path(filename).write_bytes(b"deterministic-test-woff2")

        class Subsetter:
            def __init__(self, **kwargs):
                pass

            def populate(self, *, text):
                texts.append(text)

            def subset(self, font):
                pass

        modules = {
            "fontTools": SimpleNamespace(),
            "fontTools.ttLib": SimpleNamespace(TTFont=Font),
            "fontTools.subset": SimpleNamespace(Subsetter=Subsetter, Options=object),
        }
        for patch in (
            mock.patch.dict(sys.modules, modules),
            mock.patch.object(subset_font, "INDEX_PATH", str(self.index)),
            mock.patch.object(subset_font, "download", side_effect=lambda url, dest: Path(dest).write_bytes(b"font")),
        ):
            patch.start()
            self.addCleanup(patch.stop)

    def run_main(self):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            subset_font.main()

    def render(self, source):
        self.index.write_text(source, encoding="utf-8")
        self.run_main()
        return self.index.read_text(encoding="utf-8")

    def test_replaces_real_style_not_html_comment(self):
        prefix = '<!-- <style>' + FONT + '</style> -->\n'
        result = self.render(prefix + '<style>' + FONT + '</style>')
        self.assertTrue(result.startswith(prefix))
        self.assertIn(NEW_SOURCE, result[len(prefix):])

    def test_replaces_real_style_not_script_literal(self):
        prefix = '<script>const example = \'<style>' + FONT + '</style>\';</script>\n'
        result = self.render(prefix + '<style>' + FONT + '</style>')
        self.assertTrue(result.startswith(prefix))
        self.assertIn(NEW_SOURCE, result[len(prefix):])

    def test_skips_css_comments(self):
        prefix = '<style>/* ' + FONT + ' */\n'
        result = self.render(prefix + FONT + '</style>')
        self.assertTrue(result.startswith(prefix))
        self.assertIn(NEW_SOURCE, result[len(prefix):])

    def test_skips_css_strings(self):
        prefix = '<style>.example::before{content:\'' + FONT + '\';}\n'
        result = self.render(prefix + FONT + '</style>')
        self.assertTrue(result.startswith(prefix))
        self.assertIn(NEW_SOURCE, result[len(prefix):])

    def test_braces_in_strings_do_not_truncate_rule(self):
        source = '<style>@font-face{font-family:"Nebulove";src:url("old}font.woff2");}p{color:red}</style>'
        result = self.render(source)
        self.assertNotIn('old}font', result)
        self.assertTrue(result.endswith('}p{color:red}</style>'))
        self.assertNotIn('font.woff2', result)

    def test_braces_in_comments_and_unquoted_urls(self):
        source = '<style>@font-face{/* } */font-family:Nebulove;src:url(old}font.woff2);}p{color:red}</style>'
        result = self.render(source)
        self.assertTrue(result.endswith('}p{color:red}</style>'))
        self.assertNotIn('old}font', result)

    def test_style_attributes_and_case_insensitive_rules(self):
        prefix = '<STYLE data-example=">">\n'
        result = self.render(prefix + FONT.replace('@font-face', '@FONT-FACE') + '</STYLE >')
        self.assertTrue(result.startswith(prefix))
        self.assertTrue(result.endswith('</STYLE >'))
        self.assertIn(NEW_SOURCE, result)

    def test_only_first_real_rule_is_replaced(self):
        suffix = '<style>@font-face{font-family:Other;src:url(other.woff2);}</style>'
        result = self.render('<style>' + FONT + '</style>' + suffix)
        self.assertTrue(result.endswith(suffix))
        self.assertEqual(result.count(NEW_SOURCE), 1)

    def test_comment_only_font_is_not_a_replacement_target(self):
        source = '<!-- <style>' + FONT + '</style> --><style>/* ' + FONT + ' */</style>'
        self.index.write_text(source, encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.run_main()
        self.assertEqual(self.index.read_text(encoding="utf-8"), source)
        subset_font.download.assert_not_called()

    def test_preserves_comment_markers_in_rendered_literals(self):
        source = ('<style>' + FONT + '.x::before{content:"<!--甲-->"}</style>'
                  '<script>const text = "<!--乙-->";</script><!--龘-->')
        self.render(source)
        self.assertIn('甲', self.texts[-1])
        self.assertIn('乙', self.texts[-1])
        self.assertNotIn('龘', self.texts[-1])

    def test_css_literal_font_text_is_kept_in_subset(self):
        self.render('<style>' + FONT + '.x::before{content:"@font-face{甲}"}</style>')
        self.assertIn('甲', self.texts[-1])

    def test_second_run_does_not_rewrite_identical_output(self):
        result = self.render('<style>' + FONT + '</style><p>你好 &amp; 世界</p>')
        with mock.patch.object(subset_font.os, 'replace') as replace:
            self.run_main()
        replace.assert_not_called()
        self.assertEqual(self.index.read_text(encoding="utf-8"), result)

    def test_atomic_replacement_preserves_file_permissions(self):
        for mode in (0o644, 0o640):
            with self.subTest(mode=oct(mode)):
                self.index.write_text('<style>' + FONT + '</style>', encoding="utf-8")
                self.index.chmod(mode)
                self.run_main()
                self.assertEqual(stat.S_IMODE(self.index.stat().st_mode), mode)

    def test_failed_replace_keeps_original_and_removes_temporary_file(self):
        source = '<style>' + FONT + '</style>'
        self.index.write_text(source, encoding="utf-8")
        with mock.patch.object(subset_font.os, 'replace', side_effect=OSError('write failed')):
            with self.assertRaises(OSError):
                self.run_main()
        self.assertEqual(self.index.read_text(encoding="utf-8"), source)
        self.assertEqual(list(self.index.parent.iterdir()), [self.index])

    def test_failed_font_sources_leave_original_untouched(self):
        source = '<style>' + FONT + '</style>'
        self.index.write_text(source, encoding="utf-8")
        subset_font.download.side_effect = OSError('offline')
        with self.assertRaises(SystemExit):
            self.run_main()
        self.assertEqual(subset_font.download.call_count, 3)
        self.assertEqual(self.index.read_text(encoding="utf-8"), source)
        self.assertEqual(list(self.index.parent.iterdir()), [self.index])


if __name__ == '__main__':
    unittest.main()
