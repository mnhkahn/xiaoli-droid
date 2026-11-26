#!/usr/bin/env python3
"""Publish a signed APK. API and temporary upload credentials never enter logs."""
import argparse
import json
import os
import re
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request

API = 'https://www.pgyer.com/apiv2/app/'

def request(endpoint, fields, get=False):
    encoded=urllib.parse.urlencode(fields).encode()
    req=urllib.request.Request(API+endpoint+('?' + encoded.decode() if get else ''),data=None if get else encoded,headers={'Content-Type':'application/x-www-form-urlencoded','User-Agent':'xiaoli-release'})
    with urllib.request.urlopen(req,timeout=45) as response:
        data=response.read(1024*1024)
    return json.loads(data)

def publish(apk, notes, expected_version=None, expected_code=None):
    key=os.environ.get('PGYER_API_KEY','')
    if not key: raise RuntimeError('Missing PGYER_API_KEY')
    response=request('getCOSToken',{'_api_key':key,'buildType':'android','buildInstallType':'1','buildUpdateDescription':notes})
    if response.get('code')!=0: raise RuntimeError('Pgyer upload-token request failed, code '+str(response.get('code')))
    data=response['data'];endpoint=data['endpoint'];params=data['params']
    if urllib.parse.urlsplit(endpoint).scheme!='https': raise RuntimeError('Pgyer returned an invalid upload endpoint')
    config='url = '+json.dumps(endpoint)+'\n'
    for field in ('key','signature','x-cos-security-token'):
        config+='form-string = '+json.dumps(field+'='+params[field])+'\n'
    config+='form-string = "x-cos-meta-file-name=xiaoli.apk"\n'
    result=subprocess.run(['curl','--silent','--show-error','--fail','--proto','=https','--connect-timeout','20','--max-time','300','--config','-','--form','file=@'+str(apk.resolve())],input=config,text=True,capture_output=True)
    if result.returncode: raise RuntimeError('Pgyer upload failed')
    for _ in range(60):
        info=request('buildInfo',{'_api_key':key,'buildKey':data['key']},get=True)
        if info.get('code')==0:
            build = info['data']
            if expected_version is not None and (
                build.get('buildIdentifier') != 'com.cyeam.xiaoli'
                or build.get('buildVersion') != expected_version
                or str(build.get('buildVersionNo')) != str(expected_code)
                or str(build.get('buildFileSize')) != str(apk.stat().st_size)
            ):
                raise RuntimeError('Pgyer returned a different package or version')
            shortcut = build.get('buildShortcutUrl', '')
            if not re.fullmatch(r'[A-Za-z0-9_-]+', shortcut):
                raise RuntimeError('Pgyer returned an invalid shortcut')
            return {'url': 'https://www.pgyer.com/' + shortcut,
                    'version': build.get('buildVersion'), 'versionCode': build.get('buildVersionNo')}
        if info.get('code') not in (1246,1247): raise RuntimeError('Pgyer processing failed, code '+str(info.get('code')))
        time.sleep(3)
    raise RuntimeError('Pgyer processing timed out')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('apk',type=Path);parser.add_argument('--notes',default='小李安卓助手');parser.add_argument('--output',type=Path);args=parser.parse_args()
    try:
        info=publish(args.apk,args.notes,os.environ.get('RELEASE_VERSION'),os.environ.get('RELEASE_CODE'))
        if args.output:args.output.write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n')
        print(info['url'])
    except Exception as error:
        # Never echo exception data from an HTTP response or signed upload URL.
        print('Pgyer publication failed: '+str(error) if isinstance(error,RuntimeError) else 'Pgyer publication failed: network or response error')
        raise SystemExit(1)
