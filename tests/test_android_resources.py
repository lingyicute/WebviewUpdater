"""Static Android resource contracts, supplemented by assembleRelease/lintRelease."""
from pathlib import Path
import unittest
from xml.etree import ElementTree as ET


MAIN = Path(__file__).resolve().parents[1] / 'app/src/main'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class AndroidResourceTests(unittest.TestCase):
    def test_all_xml_resources_parse(self):
        for filename in MAIN.rglob('*.xml'):
            with self.subTest(filename=str(filename)):
                ET.parse(filename)

    def test_provider_roots_only_cover_the_two_apk_download_directories(self):
        paths = ET.parse(MAIN / 'res/xml/file_paths.xml').getroot()
        self.assertEqual({node.tag for node in paths}, {'files-path', 'external-files-path'})
        self.assertEqual(len(paths), 2)
        for node in paths:
            with self.subTest(root=node.tag):
                self.assertEqual(node.attrib['path'], 'Download/')

    def test_provider_requires_explicit_uri_grants(self):
        manifest = ET.parse(MAIN / 'AndroidManifest.xml').getroot()
        providers = manifest.findall('application/provider')
        self.assertEqual(len(providers), 1)
        provider = providers[0]
        self.assertEqual(provider.attrib[ANDROID + 'exported'], 'false')
        self.assertEqual(provider.attrib[ANDROID + 'grantUriPermissions'], 'true')
        self.assertEqual(provider.attrib[ANDROID + 'authorities'], '${applicationId}.fileprovider')

    def test_network_security_forbids_cleartext(self):
        config = ET.parse(MAIN / 'res/xml/network_security_config.xml').getroot()
        self.assertEqual(config.find('base-config').attrib['cleartextTrafficPermitted'], 'false')


if __name__ == '__main__':
    unittest.main()
