import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import publish_pgyer
from release_manifest import make_manifest

class ReleaseTest(unittest.TestCase):
    def test_manifest_records_real_checksum(self):
        with tempfile.TemporaryDirectory() as d:
            apk=Path(d)/'app.apk';apk.write_bytes(b'APK')
            manifest=make_manifest(apk,'0.1.0',5,'v0.1.0','https://www.pgyer.com/test')
            self.assertEqual(64,len(manifest['sha256']))
            self.assertEqual(5,manifest['versionCode'])
            self.assertTrue(manifest['apkUrl'].endswith('/v0.1.0/xiaoli.apk'))
    @patch.dict('os.environ',{'PGYER_API_KEY':'test-secret'})
    def test_publisher_waits_for_processing(self):
        with patch.object(publish_pgyer,'request',side_effect=[{'code':0,'data':{'key':'build','endpoint':'https://upload.test','params':{'key':'k','signature':'s','x-cos-security-token':'t'}}},{'code':1246},{'code':0,'data':{'buildShortcutUrl':'xiaoli','buildVersion':'0.1.0','buildVersionNo':'5'}}]),patch.object(publish_pgyer.subprocess,'run') as run,patch.object(publish_pgyer.time,'sleep'):
            run.return_value.returncode=0
            result=publish_pgyer.publish(Path('/tmp/app.apk'),'notes')
            self.assertEqual('https://www.pgyer.com/xiaoli',result['url'])
            args=run.call_args.args[0];self.assertNotIn('test-secret',' '.join(args));self.assertNotIn('signature=s',' '.join(args))
    @patch.dict('os.environ',{'PGYER_API_KEY':'test-secret'})
    def test_rejects_a_different_published_build(self):
        responses = [
            {'code': 0, 'data': {'key': 'b', 'endpoint': 'https://upload.test',
             'params': {'key': 'b', 'signature': 's', 'x-cos-security-token': 't'}}},
            {'code': 0, 'data': {'buildIdentifier': 'other.app', 'buildShortcutUrl': 'other'}},
        ]
        with patch.object(publish_pgyer, 'request', side_effect=responses), patch.object(publish_pgyer.subprocess, 'run') as run:
            run.return_value.returncode = 0
            with self.assertRaisesRegex(RuntimeError, 'different package or version'):
                publish_pgyer.publish(Path('/tmp/test.apk'), 'notes', '1.0.0', 5)

    @patch.dict('os.environ',{'PGYER_API_KEY':'test-secret'})
    def test_rejects_plaintext_upload(self):
        with patch.object(publish_pgyer,'request',return_value={'code':0,'data':{'key':'b','endpoint':'http://bad.test','params':{}}}):
            with self.assertRaises(RuntimeError):publish_pgyer.publish(Path('/tmp/a.apk'),'notes')
if __name__=='__main__':unittest.main()
