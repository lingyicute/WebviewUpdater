"""Exercise the actual workflow's git-config commands without network access."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/font-subset.yml'


class WorkflowIdentityTests(unittest.TestCase):
    def test_event_values_are_not_interpolated_into_shell_source(self):
        commands = '\n'.join(line for line in WORKFLOW.read_text().splitlines()
                             if line.strip().startswith('git config user.'))
        self.assertNotIn('${{', commands)
        self.assertIn('COMMIT_AUTHOR_NAME:', WORKFLOW.read_text())
        self.assertIn('COMMIT_AUTHOR_EMAIL:', WORKFLOW.read_text())

    def test_git_receives_literal_email_without_executing_its_contents(self):
        for email in ('$(touch injected)@example.com', '"quoted local"@example.com',
                      'normal+tag@example.com'):
            with self.subTest(email=email), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                subprocess.run(['git', 'init', '-q', str(root)], check=True)
                commands = '\n'.join(line.strip() for line in WORKFLOW.read_text().splitlines()
                                     if line.strip().startswith('git config user.'))
                # Simulate the Actions expression substitution used by the old workflow.
                commands = commands.replace('${{ github.actor }}', 'lingyicute')
                commands = re.sub(r'\$\{\{ github\.event\.pusher\.email.*?\}\}',
                                  lambda match: email, commands)
                env = dict(os.environ, HOME=str(root), GIT_CONFIG_NOSYSTEM='1',
                           COMMIT_AUTHOR_NAME='lingyicute', COMMIT_AUTHOR_EMAIL=email)
                result = subprocess.run(['bash', '-e', '-c', commands], cwd=root, env=env,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertFalse((root / 'injected').exists(), 'event value executed as shell code')
                actual = subprocess.check_output(['git', 'config', '--get', 'user.email'],
                                                 cwd=root, env=env, text=True).rstrip('\n')
                self.assertEqual(actual, email)


if __name__ == '__main__':
    unittest.main()
